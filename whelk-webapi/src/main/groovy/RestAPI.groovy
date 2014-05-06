package se.kb.libris.whelks.api

import groovy.util.logging.Slf4j as Log
import groovy.xml.StreamingMarkupBuilder
import groovy.util.slurpersupport.GPathResult

import java.util.concurrent.*

import org.restlet.*
import org.restlet.data.*
import org.restlet.resource.*
import org.restlet.representation.*

import org.codehaus.jackson.map.*

import se.kb.libris.conch.Tools
import static se.kb.libris.conch.Tools.*
import se.kb.libris.whelks.*
import se.kb.libris.whelks.component.*
import se.kb.libris.whelks.http.*
import se.kb.libris.whelks.imports.*
import se.kb.libris.whelks.plugin.*
import se.kb.libris.whelks.result.*
import se.kb.libris.whelks.exception.*
import se.kb.libris.whelks.persistance.*
import se.kb.libris.conch.converter.MarcJSONConverter
import se.kb.libris.whelks.http.*
import se.kb.libris.util.marc.*
import se.kb.libris.util.marc.io.*


@Log
class SparqlRestlet extends BasicWhelkAPI {
    def pathEnd = "_sparql"
    def varPath = false
    String id = "SparqlAPI"

    String description = "Provides sparql endpoint to the underlying tripple store."

    @Override
    void doHandle(Request request, Response response) {
        def form = new Form(request.getEntity())
        def query = form.getFirstValue("query")

        InputStream is
        try {
            is = whelk.sparql(query)
        } catch (Exception e) {
            is = new ByteArrayInputStream(e.message.bytes)
            log.warn("Query $query resulted in error: ${e.message}", e)
            query = null
            response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST)
        }

        log.debug("Creating outputrepresentation.")

        Representation ir = new OutputRepresentation(mediaType(query)) {
            @Override
            public void write(OutputStream realOutput) throws IOException {
                byte[] b = new byte[8]
                int read
                while ((read = is.read(b)) != -1) {
                    realOutput.write(b, 0, read)
                    realOutput.flush()
                }
            }
        }

        log.debug("Sending outputrepresentation.")
        response.setEntity(ir)
    }

    MediaType mediaType(String query) {
        if (!query) {
            return MediaType.TEXT_PLAIN
        }
        if (query.toUpperCase().contains("SELECT") || query.toUpperCase().contains("ASK")) {
            return MediaType.APPLICATION_SPARQL_RESULTS_XML
        } else {
            return MediaType.APPLICATION_RDF_XML
        }
    }
}

@Log
class RemoteSearchRestlet extends BasicWhelkAPI {
    def pathEnd = "_remotesearch"
    def mapper
    
    String description = "Query API for remote search"
    String id = "RemoteSearchAPI"

    Map remoteURLs

    MarcFrameConverter marcFrameConverter

    URL metaProxyInfoUrl
    String metaProxyBaseUrl

    final String DEFAULT_DATABASE = "LC"

    def urlParams = ["version": "1.1", "operation": "searchRetrieve", "maximumRecords": "10","startRecord": "1"]

    RemoteSearchRestlet(Map settings) {
        this.metaProxyBaseUrl = settings.metaproxyBaseUrl
        // Cut trailing slashes from url
        while (metaProxyBaseUrl.endsWith("/")) {
            metaProxyBaseUrl = metaProxyBaseUrl[0..-1]
        }
        assert metaProxyBaseUrl
        metaProxyInfoUrl = new URL(settings.metaproxyInfoUrl)

        // Prepare remoteURLs by loading settings once.
        loadMetaProxyInfo(metaProxyInfoUrl)

        mapper = new ElasticJsonMapper()
    }


    List loadMetaProxyInfo(URL url) {
        def xml = new XmlSlurper(false,false).parse(url.newInputStream())

        def databases = xml.libraryCode.collect {
            def map = ["database": createString(it.@id)]
            it.children().each { node ->
                def n = node.name().toString()
                def o = node.text().toString()
                def v = map[n]
                if (v) {
                    if (v instanceof String) {
                        v = map[n] = [v]
                    }
                    v << o
                } else {
                    map[n] = o
                }
            }
            return map
        }

        remoteURLs = databases.inject( [:] ) { map, db ->
            map << [(db.database) : metaProxyBaseUrl + "/" + db.database]
        }

        return databases
    }


    void init(String wn) {
        log.debug("plugins: ${whelk.plugins}")
        marcFrameConverter = whelk.plugins.find { it instanceof FormatConverter && it.resultContentType == "application/ld+json" && it.requiredContentType == "application/x-marc-json" }
        assert marcFrameConverter
    }

    void doHandle(Request request, Response response) {
        def queryMap = request.getResourceRef().getQueryAsForm().getValuesMap()
        def query = queryMap.get("q", null)
        int start = queryMap.get("start", "0") as int
        int n = queryMap.get("n", "10") as int
        def databaseList = queryMap.get("database", DEFAULT_DATABASE).split(",") as List
        def queryStr, url
        MarcRecord record
        OaiPmhXmlConverter oaiPmhXmlConverter
        MediaType mediaType = MediaType.APPLICATION_JSON
        String output = ""

        urlParams['maximumRecords'] = n
        urlParams['startRecord'] = (start < 1 ? 1 : start)

        if (query) {
            // Weed out the unavailable databases
            databaseList = databaseList.intersect(remoteURLs.keySet() as List)
            log.debug("Remaining databases: $databaseList")
            urlParams.each { k, v ->
                if (!queryStr) {
                    queryStr = "?"
                } else {
                    queryStr += "&"
                }
                queryStr += k + "=" + v
            }
            if (!databaseList) {
                output = "{\"error\":\"Requested database is unknown or unavailable.\"}"
            }
            else {
                ExecutorService queue = Executors.newCachedThreadPool()
                def resultLists = []
                try {
                    def futures = []
                    for (database in databaseList) {
                        url = new URL(remoteURLs[database] + queryStr + "&query=" + URLEncoder.encode(query, "utf-8"))
                        log.debug("submitting to futures")
                        futures << queue.submit(new MetaproxyQuery(whelk, url, database))
                    }
                    log.info("Started all threads. Now harvesting the future")
                    for (f in futures) {
                        log.debug("Waiting for future ...")
                        def sr = f.get()
                        log.debug("Adding ${sr.numberOfHits} to ${resultLists}")
                        resultLists << sr
                    }
                } finally {
                    queue.shutdown()
                }
                // Merge results
                def results = ['hits':[:],'list':[]]
                int biggestList = 0
                for (result in resultLists) { if (result.hits.size() > biggestList) { biggestList = result.hits.size() } }
                def errors = [:]
                for (int i = 0; i <= biggestList; i++) {
                    for (result in resultLists) {
                        results.hits[result.database] = result.numberOfHits
                        try {
                            if (result.error) {
                                errors.get(result.database, [:]).put(""+i, result.error)
                            } else if (i < result.hits.size()) {
                                results.list << ['database':result.database,'data':result.hits[i].dataAsMap]
                            }
                        } catch (ArrayIndexOutOfBoundsException aioobe) {
                            log.debug("Overstepped array bounds.")
                        } catch (NullPointerException npe) {
                            log.trace("npe.")
                        }
                    }
                }
                if (errors) {
                    results['errors'] = errors
                }
                output = mapper.writeValueAsString(results)
            }
        } else if (queryMap.containsKey("databases") || request.getResourceRef().getQuery() == "databases") {
            def databases = loadMetaProxyInfo(metaProxyInfoUrl)
            output = mapper.writeValueAsString(databases)
        } else if (!query) {
            output = "{\"error\":\"Use parameter 'q'\"}"
        }
        if (!output) {
            response.setStatus(output, Status.SUCCESS_NO_CONTENT)
        } else {
            response.setEntity(output, mediaType)
        }
    }

    class MetaproxyQuery implements Callable<MetaproxySearchResult> {

        URL url
        String database
        Whelk whelk

        MetaproxyQuery(Whelk w, URL queryUrl, String db) {
            this.url = queryUrl
            this.database = db
            this.whelk = w
            assert whelk
        }

        @Override
        MetaproxySearchResult call() {
            def docStrings, results
            try {
                log.debug("requesting data from url: $url")
                def xmlRecords = new XmlSlurper().parseText(url.text).declareNamespace(zs:"http://www.loc.gov/zing/srw/", tag0:"http://www.loc.gov/MARC21/slim")
                int numHits = xmlRecords.'zs:numberOfRecords'.toInteger()
                docStrings = getXMLRecordStrings(xmlRecords)
                results = new MetaproxySearchResult(database, numHits)
                for (docString in docStrings) {
                    def record = MarcXmlRecordReader.fromXml(docString)
                    // Not always available (and also unreliable)
                    //id = record.getControlfields("001").get(0).getData()

                    log.trace("Marcxmlrecordreader for done")

                    def jsonRec = MarcJSONConverter.toJSONString(record)
                    log.trace("Marcjsonconverter for done")
                    def xMarcJsonDoc = new Document()
                    .withData(jsonRec.getBytes("UTF-8"))
                    .withContentType("application/x-marc-json")
                    //Convert xMarcJsonDoc to ld+json
                    def jsonDoc = marcFrameConverter.doConvert(xMarcJsonDoc)
                    if (!jsonDoc.identifier) {
                        jsonDoc.identifier = this.whelk.mintIdentifier(jsonDoc)
                    }
                    log.trace("Marcframeconverter done")

                    results.addHit(jsonDoc)
                }
            } catch (org.xml.sax.SAXParseException spe) {
                log.error("Failed to parse XML: ${url.text}")
                results = new MetaproxySearchResult(database, 0)
                results.error = "Failed to parse XML (${spe.message}))"
            } catch (java.net.ConnectException ce) {
                log.error("Connection failed", ce)
                results = new MetaproxySearchResult(database, 0)
                results.error = "Connection with metaproxy failed."
            } catch (Exception e) {
                log.error("Could not convert document from $docStrings", e)
                results = new MetaproxySearchResult(database, 0)
                results.error = "Failed to convert results to JSON."
            }
            return results
        }
    }

    class MetaproxySearchResult extends SearchResult {

        String database, error

        MetaproxySearchResult(String db, int nrHits) {
            super(nrHits)
            this.database = db
        }
    }

    List<String> getXMLRecordStrings(xmlRecords) {
        def xmlRecs = new ArrayList<String>()
        def allRecords = xmlRecords?.'zs:records'?.'zs:record'
        for (record in allRecords) {
            def recordData = record.'zs:recordData'
            def marcXmlRecord = createString(recordData.'tag0:record')
            if (marcXmlRecord) {
                xmlRecs << removeNamespacePrefixes(marcXmlRecord)
            }
        }
        return xmlRecs
    }

    String createString(GPathResult root) {
        return new StreamingMarkupBuilder().bind {
            out << root
        }
    }

    String removeNamespacePrefixes(String xmlStr) {
        return xmlStr.replaceAll("tag0:", "").replaceAll("zs:", "")
    }
}
@Log
class SearchRestlet extends BasicWhelkAPI {
    def pathEnd = "{indexType}/_search"
    def varPath = true
    String id = "Search"
    String description = "Generic search query API. User parameters \"q\" for querystring, and optionally \"facets\" and \"boost\"."
    def config


    SearchRestlet(indexTypeConfig) {
        this.config = indexTypeConfig
        if (config.path) {
            this.pathEnd = config.path
        }
    }

    @Override
    void doHandle(Request request, Response response) {
        def queryMap = createProperFormMap(request.getResourceRef().getQueryAsForm()) //.getValuesMap()
        def indexType = request.attributes?.indexType ?: config.defaultIndexType
        def indexConfig = config.indexTypes[indexType]
        def boost = queryMap.boost ?: indexConfig?.defaultBoost?.split(",")
        def facets = queryMap.facets ?: indexConfig?.queryFacets?.split(",")
        def elasticQuery = new ElasticQuery(queryMap)
        if (queryMap.f) {
            elasticQuery.query += " " + queryMap.f
        }
        elasticQuery.indexType = indexType
        if (facets) {
            for (f in facets) {
                elasticQuery.addFacet(f)
            }
        }
        if (boost) {
            for (b in boost) {
                if (b.size() > 0) {
                    def (k, v) = b.split(":")
                    elasticQuery.addBoost(k, Long.parseLong(v))
                }
            }
        }
        def fields = indexConfig?.get("queryFields")
        if (fields && fields.size() > 0) {
            elasticQuery.fields = fields
        }

        elasticQuery.highlights = indexConfig?.get("queryFields")
        if (!queryMap['sort'] && !queryMap['order']) {
            elasticQuery.sorting = indexConfig?.get("sortby")
        }
        try {
            def callback = queryMap.get("callback")
            def jsonResult =
            (callback ? callback + "(" : "") +
            performQuery(elasticQuery) +
            (callback ? ");" : "")

            response.setEntity(jsonResult, MediaType.APPLICATION_JSON)

        } catch (WhelkRuntimeException wrte) {

            response.setStatus(Status.CLIENT_ERROR_NOT_FOUND, wrte.message)
        }
    }

    String performQuery(elasticQuery) {
        long startTime = System.currentTimeMillis()
        //def elasticQuery = new ElasticQuery(queryMap)
        log.debug("elasticQuery: ${elasticQuery.query}")
        def results
        try {

            log.debug("Handling search request with indextype $elasticQuery.indexType")
            def indexConfig = config.indexTypes?.get(elasticQuery.indexType, null)

            log.debug("Query $elasticQuery.query Fields: ${elasticQuery.fields} Facets: ${elasticQuery.facets}")
            results = this.whelk.search(elasticQuery)
            def keyList = indexConfig?.get("resultFields")
            log.info("keyList: $keyList")
            def extractedResults = keyList != null ? results.toJson(keyList) : results.toJson()

            return extractedResults
        } finally {
            this.logMessage = "Query [" + elasticQuery?.query + "] completed resulting in " + results?.numberOfHits + " hits"
        }
    }

    Map createProperFormMap(Form form) {
        def formMap = [:].withDefault{ [] }
        for (name in form.names) {
            def va = form.getValuesArray(name) as List
            if (va.size() == 1) {
                va = va.first().split(",")
            }
            formMap.get(name).addAll(va)
        }
        return formMap
    }
}


@Log
class DocumentRestlet extends BasicWhelkAPI {

    def pathEnd = "{identifier}"
    def varPath = true

    String description = "A GET request with identifier loads a document. A PUT request stores a document. A DELETE request deletes a document."
    String id = "DocumentAPI"

    DocumentRestlet(Whelk whelk) {
        this.whelk = whelk
    }

    def _escape_regex(str) {
        def escaped = new StringBuffer()
        str.each {
            if (it == "{" || it == "}") {
                escaped << "\\"
            }
            escaped << it 
        }
        return escaped.toString()
    }

    def determineDisplayMode(path) {
        if (path.endsWith("/meta")) {
            return [path[0 .. -6], DisplayMode.META]
        }
        return [path, DisplayMode.DOCUMENT]
    }

    void doHandle(Request request, Response response) {
        log.debug("DocumentAPI request with path: $path and pathEnd: $pathEnd Whelk contentroot: ${this.whelk.contentRoot}")
        log.debug("Identifier: " + request.attributes["identifier"])
        String path = path.replaceAll(_escape_regex(pathEnd), request.attributes["identifier"])
        log.debug "Path: $path"
        def mode = DisplayMode.DOCUMENT
        def headers = request.attributes.get("org.restlet.http.headers")
        (path, mode) = determineDisplayMode(path)
        if (request.method == Method.GET) {
            log.debug "Request path: ${path}"
            log.debug " DisplayMode: $mode"
            def version = request.getResourceRef().getQueryAsForm().getValuesMap().get("version", null)
            def accepting = headers.find {
                    it.name.equalsIgnoreCase("accept")
                }?.value?.split(",").collect {
                    int last = (it.indexOf(';') == -1 ? it.length() : it.indexOf(';'))
                    it.substring(0,last)
                }

            log.debug("Accepting $accepting")
            try {
                log.debug("We want version $version")
                def d = whelk.get(new URI(path), version, accepting)
                log.debug("Document received from whelk: $d")
                if (d && (mode == DisplayMode.META || !d.entry['deleted'])) {
                    if (mode == DisplayMode.META) {
                        response.setEntity(d.metadataAsJson, MediaType.APPLICATION_JSON)
                    } else {
                        response.setEntity(d.dataAsString, LibrisXLMediaType.getMainMediaType(d.contentType))
                    }
                    response.entity.setTag(new Tag(d.timestamp as String, false))
                } else {
                    log.debug("Failed to find a document with URI $path")
                    response.setStatus(Status.CLIENT_ERROR_NOT_FOUND)
                }
            } catch (WhelkRuntimeException wrte) {
                response.setStatus(Status.CLIENT_ERROR_NOT_FOUND, wrte.message)
            }
        } else if (request.method == Method.POST) {
            try {
                log.trace("headers: $headers")
                log.debug("request: $request")
                def link = headers.find { it.name.equals("link") }?.value
                //def dataset = headers.find { it.name == "dataset" }?.getValue("").split(",")
                Document doc = new Document().withData(Tools.normalizeString(request.entityAsText)).withEntry(["contentType":request.entity.mediaType.toString()])
                if (link != null) {
                    log.trace("Adding link $link to document...")
                    doc = doc.withLink(link)
                }
                doc = this.whelk.sanityCheck(doc)
                def identifier = this.whelk.add(doc)
                response.setEntity(doc.dataAsString, LibrisXLMediaType.getMainMediaType(doc.contentType))
                response.entity.setTag(new Tag(doc.timestamp as String, false))
                log.debug("Saved document $identifier")
                response.setStatus(Status.REDIRECTION_SEE_OTHER, "Thank you! Document ingested with id ${identifier}")
                log.debug("Redirecting with location ref " + request.getRootRef().toString() + identifier)
                response.setLocationRef(request.getRootRef().toString() + "${identifier}")
            } catch (WhelkRuntimeException wre) {
                response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST, wre.message)
            }
        } else if (request.method == Method.PUT) {
            try {
                if (path == "/") {
                    throw new WhelkRuntimeException("PUT requires a proper URI.")
                }
                def identifier
                log.trace("headers: $headers")
                def link = headers.find { it.name.equals("link") }?.value
                def entry = [
                    "identifier":path,
                    "contentType":request.entity.mediaType.toString(),
                    "dataset":getDatasetBasedOnPath(path)
                    ]
                def meta = null
                if (link != null) {
                    log.trace("Adding link $link to document...")
                    meta = [
                        "links": [
                            ["identifier":link,"type":""]
                        ]
                    ]
                }

                // Check If-Match
                def ifMatch = headers.find { it.name.equalsIgnoreCase("If-Match") }?.value
                if (ifMatch
                    && this.whelk.get(new URI(path))
                    && this.whelk.get(new URI(path))?.timestamp as String != ifMatch) {
                    response.setStatus(Status.CLIENT_ERROR_PRECONDITION_FAILED, "The resource has been updated by someone else. Please refetch.")
                } else {
                    try {
                        identifier = this.whelk.add(
                            request.entityAsText.getBytes("UTF-8"),
                            entry,
                            meta
                            )
                        response.setStatus(Status.REDIRECTION_SEE_OTHER, "Thank you! Document ingested with id ${identifier}")
                        response.setLocationRef(request.getOriginalRef().toString())
                    } catch (WhelkAddException wae) {
                        log.warn("Whelk failed to store document: ${wae.message}")
                        response.setStatus(Status.CLIENT_ERROR_UNSUPPORTED_MEDIA_TYPE, wae.message)
                    }
                }
            } catch (WhelkRuntimeException wre) {
                response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST, wre.message)
            }
        }
        else if (request.method == Method.DELETE) {
            try {
                whelk.remove(new URI(path))
                response.setStatus(Status.SUCCESS_NO_CONTENT)
            } catch (WhelkRuntimeException wre) {
                response.setStatus(Status.SERVER_ERROR_INTERNAL, wre.message)
            }
        }
    }

    private String getDatasetBasedOnPath(path) {
        String ds = ""
        def elements = path.split("/")
        int i = 1
        while (ds.length() == 0 || ds == whelk.id) {
            ds = elements[i++]
        }
        return ds
    }
}

@Log
class DumpRestlet extends BasicWhelkAPI {
    def pathEnd = "{dataset}/_dump"
    def varPath = true
    String id = "DataDumper"

    String description = "Dump an entire dataset."

    void doHandle(Request request, Response response) {
        def dataset = request.attributes["dataset"]
        log.debug("Dataset: $dataset")
        dataset = (dataset == "all" ? null : dataset)

        def w = this.whelk

        log.debug("Creating outputrepresentation.")
        Representation ir = new OutputRepresentation(MediaType.APPLICATION_JSON) {
            @Override
            public void write(OutputStream realOutput) throws IOException {
                realOutput.write("[".bytes)
                boolean insertComma = false
                for (document in w.loadAll(dataset)) {
                    if (document.isJson()) {
                        if (insertComma) {
                            realOutput.write(",".bytes)
                        }
                        realOutput.write(document.getData())
                        realOutput.flush()
                        insertComma = true
                    } else {
                        log.warn("Document ${document.identifier} has content-type ${document.contentType}")
                    }
                }
                realOutput.write("]".bytes)
                realOutput.close()
            }
        }

        log.debug("Sending outputrepresentation.")
        response.setEntity(ir)
    }
}






/*
 * Superclasses and interfaces.
 */
interface RestAPI extends API {
    String getPath()
}

@Log
abstract class BasicWhelkAPI extends Restlet implements RestAPI {
    Whelk whelk

    def pathEnd = ""
    def varPath = false
    boolean enabled = true

    String logMessage = "Handled #REQUESTMETHOD# for #API_ID#"

    @Override
    void setWhelk(Whelk w) {
        this.whelk = w
    }
    @Override
    void init(String w) {}

    String getPath() {
        def elems = [(this.whelk instanceof HttpWhelk ? this.whelk.contentRoot : this.whelk.id), getPathEnd()]
        return "/" + elems.findAll { it.length() > 0 }.join("/")
    }

    /**
     * For discovery API.
     */
    abstract String getDescription();

    private String getModifiedPathEnd() {
        if (getPathEnd() == null || getPathEnd().length() == 0) {
            return ""
        } else {
            return "/" + getPathEnd()
        }
    }

    @Override
    int compareTo(Plugin p) {
        return (this.getOrder() - p.getOrder());
    }

    abstract void doHandle(Request request, Response response)

    void handle(Request request, Response response) {
        long startTime = System.currentTimeMillis()
        doHandle(request, response)
        def headers = request.attributes.get("org.restlet.http.headers")
        def remote_ip = headers.find { it.name.equalsIgnoreCase("X-Forwarded-For") }?.value ?: request.clientInfo.address
        log.info(logMessage.replaceAll("#REQUESTMETHOD#", request.method.toString()).replaceAll("#API_ID#", this.id) + " from $remote_ip in " + (System.currentTimeMillis() - startTime) + " milliseconds.")
    }
}

@Log
class DiscoveryAPI extends BasicWhelkAPI {

    ObjectMapper mapper = new ObjectMapper()

    String description = "Discovery API"
    String pathEnd = "discovery"
    String id = "DiscoveryAPI"

    DiscoveryAPI(Whelk w) {
        this.whelk = w
    }

    @Override
    void doHandle(Request request, Response response) {
        def info = [:]
        info["whelk"] = whelk.id
        info["apis"] = whelk.getAPIs().collect {
            [ [ "path" : (it.path) ,
                "description" : (it.description) ]
            ] }
        log.debug("info: $info")

        response.setEntity(mapper.writeValueAsString(info), MediaType.APPLICATION_JSON)
    }
}

enum DisplayMode {
    DOCUMENT, META
}

@Log
class RootRouteRestlet extends BasicWhelkAPI {

    String description = "Whelk root routing API"
    String id = "RootRoute"
    def pathEnd = ""

    RootRouteRestlet(Whelk whelk) {
        this.whelk = whelk
    }

    @Override
    String getPath() {
        if (this.whelk.contentRoot == "") {
            return "/"
        } else return "/" + this.whelk.contentRoot + "/"
    }

    @Override
    void doHandle(Request request, Response response) {
        def discoveryAPI
        def documentAPI
        //GET redirects to DiscoveryAPI
        if (request.method == Method.GET) {
            discoveryAPI = new DiscoveryAPI(this.whelk)
            def uri = new URI(discoveryAPI.path)
            discoveryAPI.handle(request, response)
          //POST saves new document
        } else if (request.method == Method.POST) {
            try {
                def identifier
                Document doc = null
                def headers = request.attributes.get("org.restlet.http.headers")
                log.trace("headers: $headers")
                log.debug("request: $request")
                def link = headers.find { it.name.equals("link") }?.value
                doc = new Document().withData(Tools.normalizeString(request.entityAsText)).withEntry(["contentType":request.entity.mediaType.toString()])
                if (link != null) {
                    log.trace("Adding link $link to document...")
                    doc = doc.withLink(link)
                }
                doc = this.whelk.sanityCheck(doc)
                identifier = this.whelk.add(doc)
                response.setEntity(doc.dataAsString, LibrisXLMediaType.getMainMediaType(doc.contentType))
                response.entity.setTag(new Tag(doc.timestamp as String, false))
                log.debug("Saved document $identifier")
                response.setStatus(Status.REDIRECTION_SEE_OTHER, "Thank you! Document ingested with id ${identifier}")
                log.debug("Redirecting with location ref " + request.getRootRef().toString() + identifier)
                response.setLocationRef(request.getRootRef().toString() + "${identifier}")
            } catch (WhelkRuntimeException wre) {
                response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST, wre.message)
            }
        } else if (request.method == Method.PUT) {
            log.debug("Received PUT request for ${this.whelk.id}")
            documentAPI = new DocumentRestlet(this.whelk)
            if (request.attributes?.get("identifier") != null) {
                documentAPI.handle(request, response)
            } else {
                response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST)
            }
        }
    }
}

/*
 * Possibly unused API:s
 */

@Log
class HttpAPIRestlet extends BasicWhelkAPI {
    def pathEnd = "_sparql"
    def varPath = false
    String id = "SparqlAPI"

    String description = "Provides sparql endpoint to the underlying tripple store."

    HttpAPIRestlet(Map settings) {
        super(settings)
        this.pathEnd = settings['pathEnd']
        this.varPath = settings.get('varPath', false)
        assert this.pathEnd
    }

    @Override
    void doHandle(Request request, Response response) {
        def form = new Form(request.getEntity())
        def query = form.getFirstValue("query")

        InputStream is
        try {
            is = whelk.sparql(query)
        } catch (Exception e) {
            is = new ByteArrayInputStream(e.message.bytes)
            log.warn("Query $query resulted in error: ${e.message}", e)
            query = null
            response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST)
        }

        log.debug("Creating outputrepresentation.")

        Representation ir = new OutputRepresentation(mediaType(query)) {
            @Override
            public void write(OutputStream realOutput) throws IOException {
                byte[] b = new byte[8]
                int read
                while ((read = is.read(b)) != -1) {
                    realOutput.write(b, 0, read)
                    realOutput.flush()
                }
            }
        }

        log.debug("Sending outputrepresentation.")
        response.setEntity(ir)
    }

    MediaType mediaType(String query) {
        if (!query) {
            return MediaType.TEXT_PLAIN
        }
        if (query.toUpperCase().contains("SELECT") || query.toUpperCase().contains("ASK")) {
            return MediaType.APPLICATION_SPARQL_RESULTS_XML
        } else {
            return MediaType.APPLICATION_RDF_XML
        }
    }
}


@Log
class CompleteExpander extends BasicWhelkAPI {
    def pathEnd = "_expand/{identifier}"
    def varPath = true
    String id = "CompleteExpander"
    String description = "Provides useful information about authorities."

    void doHandle(Request request, Response response) {
        def identifier, result, relator, authDoc, resultMap, authDataMap, idQuery
        def mapper = new ObjectMapper()

        if (!request.attributes.get("identifier")) {
            response.setStatus(Status.CLIENT_ERROR_BAD_REQUEST)
        } else {
            identifier = request.attributes.identifier
            authDoc = whelk.get(new URI(identifier))
            if (!authDoc) {
                response.setStatus(Status.CLIENT_ERROR_NOT_FOUND)
            } else {
                authDataMap = getDataAsMap(authDoc)
                idQuery = "\\/resource\\/" + identifier.replace("/", "\\/")

                if (authDataMap.about."@type" == "Person") {
                    result = whelk.search(new ElasticQuery(idQuery).addField("about.instanceOf.attributedTo.@id").addFacet("about.@type"))
                    relator = "attributedTo"
                    if (result.numberOfHits == 0) {
                        result = whelk.search(new ElasticQuery(idQuery).addField("about.instanceOf.influencedBy.@id").addFacet("about.@type"))
                        relator = "influencedBy"
                    }
                    if (result.numberOfHits > 0) {
                        resultMap = result.toMap(["about.@type", "about.title.titleValue", "originalCatalogingAgency.name", "function", "exampleTitle"])
                        resultMap.list.eachWithIndex() { r, i ->
                            if (resultMap.list[i].get("data", null)) {
                                resultMap.list[i].data["function"] = relator
                                //TODO: number of holds
                                if (relator.equals("attributedTo")) {
                                    resultMap["extraKnowledge"] = ["exampleTitle" : resultMap.list[i].data.about.title.titleValue]
                                }
                            }
                        }
                    }

                }  else if (authDataMap.about."type" == "Concept") {
                    //TODO
                }

                if (!resultMap) {
                    response.setStatus(Status.CLIENT_ERROR_NOT_FOUND)
                } else {
                    response.setEntity(mapper.writeValueAsString(resultMap), MediaType.APPLICATION_JSON)
                }

            }
        }
    }
}

@Deprecated
@Log
class AutoComplete extends BasicWhelkAPI {

    def pathEnd = "_complete"

    def namePrefixes = []
    def extraInfo = []
    def sortby = []

    def queryType = "q"

    String types
    String description = "Search API for autocompletion. For person-completion: use pathend _complete with parameter name. For concept-completion: use _subjcomplete pathend with parameter concept."
    String id = "AutoComplete"

    /*
    def void addNamePrefix(String prefix) {
    namePrefixes << prefix
    }
     */

    AutoComplete(Map lists) {
        namePrefixes.addAll(lists.get("queryFields"))
        extraInfo.addAll(lists.get("infoFields"))
        types = lists.get("indexTypes")
        sortby = lists.get("sortby")
        if (lists.get("pathEnd", null)) {
            this.pathEnd = lists.get("pathEnd")
        }
        if (lists.get("queryType", null)) {
            this.queryType = lists.get("queryType")
        }
    }

    String splitName(String name) {
        def np = []
        for (n in name.split(/[\s-_]/)) {
            n = n.replaceAll(/\W$+^\*/, "")
            if (n.length() > 1 && n[-1] != ' ' && n[-1] != '*') {
                np << n+"*"
            } else if (n) {
                np << n
            }
        }
        log.debug(np.join(" "))
        return np.join(" ")
    }

    @Override
    void doHandle(Request request, Response response) {
        def querymap = request.getResourceRef().getQueryAsForm().getValuesMap()
        log.debug("Handling autocomplete request with queryType: $queryType...")
        String queryStr = querymap.get(queryType, null)
        log.debug("QueryStr: $queryStr")
        def callback = querymap.get("callback")
        if (queryStr) {
            queryStr = splitName(queryStr)
            log.debug("Query fields added to highlight: $namePrefixes")
            def query = new ElasticQuery(queryStr)
            query.highlights = namePrefixes
            query.sorting = sortby
            query.fields = namePrefixes
            query.addBoost(namePrefixes[0], 10)
            query.indexType = types

            def results = this.whelk.search(query)

            def jsonResult = 
            (callback ? callback + "(" : "") +
                new SuggestResultsConverter(results, [namePrefixes[0]], extraInfo).toJson() +
            (callback ? ");" : "")

            response.setEntity(jsonResult, MediaType.APPLICATION_JSON)
        } else {
            response.setEntity('{"error":"QueryParameter \"name\", \"concept\" or \"q\" is missing."}', MediaType.APPLICATION_JSON)
        }
    }
}

@Log
class SuggestResultsConverter {
    def results
    ObjectMapper mapper
    List mainFields
    List supplementalFields

    SuggestResultsConverter(SearchResult r, List mfs, List sfs) {
        this.results = r
        this.mapper = new ObjectMapper()
        this.mainFields = mfs
        this.supplementalFields = sfs
    }

    String toJson() {
        def list = []
        def out = [:]
        results.hits.each {
            def data = mapper.readValue(it.dataAsString, Map)
            data.remove("unknown")
            def doc = ["data":data]
            doc.identifier = it.identifier
            if (it.identifier.toString() =~ /\/auth\/\d+/) {
                doc["authorized"] = true
            }
            list << doc
        }
        out["hits"] = results.numberOfHits
        out["list"] = list
        return mapper.writeValueAsString(out)
    }

    @Deprecated
    def mapAuthRecord(id, r) {
        def name = [:]
        name["identifier"] = id
        log.debug("hl : ${r.highlight} (${r.highlight.getClass().getName()})")
        boolean mainhit = r.highlight.any { it.key in mainFields }
        log.debug("mainFields: $mainFields")
        name[mainFields[0]] = Tools.getDeepValue(r, mainFields[0])
        if (!mainhit) {
            name["found_in"] = r.highlight.findAll { !(it.key in mainFields) }//.collect { it.value }[0]
        }
        for (field in supplementalFields) {
            def dv = Tools.getDeepValue(r, field)
            log.trace("dv $field : $dv")
            if (dv) {
                name[field] = dv
            }
        }
        name["authorized"] = true

        return name
    }

    @Deprecated
    def mapBibRecord(id, r) {
        def name = [:]
        name["identifier"] = id
        name[mainFields[0]] = Tools.getDeepValue(r, mainFields[0])
        log.debug("highlight: ${r.highlight}")
        for (field in supplementalFields) {
            def dv = Tools.getDeepValue(r, field)
            log.trace("dv $field : $dv")
            if (dv) {
                name[field] = dv
            }
        }
        return name
    }
}


@Log
class DefinitionDataRestlet extends Directory implements RestAPI {
    Whelk whelk
    String id = "DefinitionDataAPI"
    String path = null
    boolean enabled = false
    def varPath = false
    void init(String w) {}

    DefinitionDataRestlet(String fileRoot) {
        super(null, toAbsoluteFileUri(fileRoot) as String)
    }

    static URI toAbsoluteFileUri(String path) {
        if (path.indexOf(":") == -1) {
            path = path.startsWith("/")? path :
                    System.getProperty("user.dir") + "/" + path
            path = "file://" + path
        }
        if (!path.endsWith("/")) {
            path += "/"
        }
        return new URI(path)
    }

}


@Deprecated
@Log
class MetadataSearchRestlet extends BasicWhelkAPI {
    def pathEnd = "_metasearch"
    def mapper

    String description = "Query API for metadata search."
    String id = "MetadataSearchAPI"

    void doHandle(Request request, Response response) {
        mapper = new ObjectMapper()
        def queryMap = request.getResourceRef().getQueryAsForm().getValuesMap()
        def link = queryMap.get("link", null)
        def tag = queryMap.get("tag", null)
        def queryObj
        //def queryStr
        def results
        def outjson = new StringBuilder()
        def records = []

        if (link && tag) {
            queryObj = new ElasticQuery("${link} AND ${tag}")
        } else if (link) {
            queryObj = new ElasticQuery(link)
        } else if (tag) {
            queryObj = new ElasticQuery(tag)
        }

        if (queryObj) {
            queryObj.indexType = "Indexed:Metadata"
            results = this.whelk.search(queryObj)

            results.hits.each {
                def identifier = it.identifier.toString()
                def d = whelk.get(new URI(identifier))
                if (d) {
                    records << d
                }
            }

            outjson << "{ \"list\": ["
            records.eachWithIndex() { it, i ->
                if (i > 0) {
                    outjson << ","
                }
                outjson << it.dataAsString
            }
            outjson << "] }"
            response.setEntity(outjson.toString(), MediaType.APPLICATION_JSON)

        } else {
            response.setEntity('{"Error":"Use parameter \"link=<uri>\". and/or \"tag=<sigel>\"}', MediaType.APPLICATION_JSON)
        }
    }

}


