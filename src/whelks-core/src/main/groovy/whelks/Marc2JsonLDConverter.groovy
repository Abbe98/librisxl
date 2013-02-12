package se.kb.libris.whelks.plugin

import se.kb.libris.whelks.*
import se.kb.libris.whelks.basic.*
import se.kb.libris.whelks.exception.*

import groovy.util.logging.Slf4j as Log

import org.codehaus.jackson.map.ObjectMapper


@Log
class Marc2JsonLDConverter extends MarcCrackerAndLabelerIndexFormatConverter implements FormatConverter {

    String requiredContentType = "application/json"
    def marcref

    Marc2JsonLDConverter() {
        mapper = new ObjectMapper()
        InputStream is = this.getClass().getClassLoader().getResourceAsStream("marc_refs.json")
        this.marcref = mapper.readValue(is, Map)
    }

    def facit = ["100" : [
                    "a" : "preferredNameForThePerson",
                    "d" : "authorDate"
                    ],
                 "020" : [
                    "a" : "isbn",
                    "c" : false
                    ]
                ]

                /*
    def postProcessors = [
        "preferredNameForThePerson": { return split on "," into givenName, surname }
    ]
    */

    Map mapDefault(String code, def value) {
        if (facit[code]) {
            return [(facit[code]): value]
        } else {
            return ["raw" : [(code): value]]
        }
    }

    Map mapDefault(String code, Map json) {
        boolean complete = true
        def out = [:]
        log.trace("mapDefault: $code = $json")
        json.get("subfields").each {
            it.each { k, v ->
                def label = facit?.get(code)?.get(k)
                if (label) {
                    out[label] = v
                } else if (label == null) {
                    complete = false
                }
            }
        }
        if (complete) {
            return out
        }
        return ["raw": [(code):json]]
    }


    def toTheDungeon(code, json) {
    }

    def mapIdentifier(code, json) {
        def out = [:]
        boolean complete = true
        json["subfields"].each {
            it.each { key, value ->
                switch (key) {
                    case "a":
                        out["identifierForTheManifestation"] = value
                        out["isbn"] = value.replaceAll("[^\\d]", "")
                        break
                    case "c":
                        break
                    default:
                        complete = false
                        break
                }
            }
        }
        if (complete) {
            return out
        }
        return ["raw": [(code):json]]
    }

    def mapPerson(code, json) {
        println "json: $json"
        def out = [:]
        boolean complete = true
        log.trace("subfields: " + json['subfields'])
        json['subfields'].each { it.each { key, value ->
            switch (key) {
                case "a":
                    out["preferredNameForThePerson"] = value
                    if (json["ind1"] == "1") {
                        def n = value.split(", ")
                        out["surname"] = n[0]
                        out["givenName"] = n[1]
                        out["name"] = n[1] + " " + n[0]
                    } else {
                        out["name"] = value
                    }
                    break;
                case "d":
                    def d = value.split("-")
                    out["dateOfBirth"] = ["@type":"year", "@value": d[0]]
                    if (d.length > 1) {
                        out["dateOfDeath"] = ["@type":"year", "@value": d[1]]
                    }
                    break;
                default:
                    complete = false
                    break;
            }
        } }
        if (complete) {
            return ["authorList": [out]]
        } else {
            return ["raw": [(code):json]]
        }
    }

    def mapField(code, json, outjson) {
        switch(code) {
            case "020":
                log.trace("injson: $json")
                outjson = mergeMap(outjson, mapIdentifier(code, json))
                break
            case "100":
            case "700":
                outjson = mergeMap(outjson, mapPerson(code, json))
                break;
            default:
                outjson = mergeMap(outjson, mapDefault(code, json))
                log.trace("OutJson now: $outjson")
                break;
        }
        return outjson
    }

    Map mergeMap(origmap, newmap) {
        newmap.each { key, value ->
            if (origmap.containsKey(key)) {
                if (!(origmap.get(key) instanceof List)) {
                    origmap[key] = [origmap[key]]
                }
                origmap[key] << value
            } else {
                origmap[key] = value
            }
        }

        origmap
    }

    def createJson(URI identifier, Map injson) {
        def outjson = [:]
        def pfx = identifier.toString().split("/")[1]
        outjson["@context"] = "http://libris.kb.se/contexts/libris.jsonld"
        outjson["@id"] = identifier.toString()
        // Workaround to prevent original data from being changed
        //outjson["marc21"] = mapper.readValue(mapper.writeValueAsBytes(injson), Map)
        injson = rewriteJson(identifier, injson)
        log.trace("Leader: ${injson.leader}")
        injson.leader.subfields.each { 
            it.each { lkey, lvalue ->
                lvalue = lvalue.trim()
                if (lvalue && !(lvalue =~ /^\|+$/)) {
                    outjson[lkey] = lvalue
                }
            }
        }
        injson.fields.each {
            log.trace("Working on json field $it")
            it.each { fkey, fvalue ->
                outjson = mapField(fkey, fvalue, outjson)
                log.trace("outjson: $outjson")
                /*
                if ((fkey as int) > 5 && (fkey as int) < 9) {
                    fvalue["subfields"].each {
                        it.each { skey, svalue ->
                            svalue = svalue.trim()
                            if (svalue && !(svalue =~ /^\|+$/)) {
                                outjson[skey] = svalue
                            }
                        }
                    }
                } else {
                    log.trace("Value: $fvalue")
                    if (marcref[pfx][fkey]) {
                        log.trace("Found a reference: " +marcref[pfx][fkey])
                        fvalue["subfields"].each {
                            it.each { skey, svalue ->
                                def label  = marcref[pfx][fkey][skey]
                                def linked = marcref[pfx][fkey]["_linked"]
                                if (linked) {
                                    log.trace("Create new entity for $fkey")
                                    createEntity(fvalue)
                                } else if (label) {
                                    if (outjson[label]) {
                                        log.trace("Adding $svalue to outjson")
                                        if (outjson[label] instanceof List) {
                                            outjson[label] << svalue
                                        } else {
                                            def l = []
                                            l << outjson[label]
                                            l << svalue
                                            outjson[label] = l
                                        }
                                    } else {
                                        log.trace("Inserting $svalue in outjson")
                                        outjson[label] = svalue
                                    }
                                }
                            }
                        }
                    }
                }
                */
            }
        }
        return outjson
    }

    /*
    def createJson(URI identifier, Map injson) {
        def outjson = [:]
        def pfx = identifier.toString().split("/")[1]
        outjson["@context"] = "http://libris.kb.se/contexts/libris.jsonld"
        outjson["@id"] = identifier.toString()
        // Workaround to prevent original data from being changed
        //outjson["marc21"] = mapper.readValue(mapper.writeValueAsBytes(injson), Map)
        injson = rewriteJson(identifier, injson)
        log.trace("Leader: ${injson.leader}")
        injson.leader.subfields.each { 
            it.each { lkey, lvalue ->
                lvalue = lvalue.trim()
                if (lvalue && !(lvalue =~ /^\|+$/)) {
                    outjson[lkey] = lvalue
                }
            }
        }
        injson.fields.each {
            log.trace("Working on json field $it")
            it.each { fkey, fvalue ->
                if ((fkey as int) > 5 && (fkey as int) < 9) {
                    fvalue["subfields"].each {
                        it.each { skey, svalue ->
                            svalue = svalue.trim()
                            if (svalue && !(svalue =~ /^\|+$/)) {
                                outjson[skey] = svalue
                            }
                        }
                    }
                } else {
                    log.trace("Value: $fvalue")
                    if (marcref[pfx][fkey]) {
                        log.trace("Found a reference: " +marcref[pfx][fkey])
                        fvalue["subfields"].each {
                            it.each { skey, svalue ->
                                def label  = marcref[pfx][fkey][skey]
                                def linked = marcref[pfx][fkey]["_linked"]
                                if (linked) {
                                    log.trace("Create new entity for $fkey")
                                    createEntity(fvalue)
                                } else if (label) {
                                    if (outjson[label]) {
                                        log.trace("Adding $svalue to outjson")
                                        if (outjson[label] instanceof List) {
                                            outjson[label] << svalue
                                        } else {
                                            def l = []
                                            l << outjson[label]
                                            l << svalue
                                            outjson[label] = l
                                        }
                                    } else {
                                        log.trace("Inserting $svalue in outjson")
                                        outjson[label] = svalue
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return outjson
    }
    */

    def createEntity(data) {
    }


    @Override
    List<Document> convert(Document idoc) {
        outdocs = []
        if (doc.contentType == this.requiredContentType) {
            def injson = mapper.readValue(doc.dataAsString, Map)
            outdocs << new BasicDocument(doc).withData(mapper.writeValueAsBytes(createJson(doc.identifier, injson)))
        } else {
            log.warn("This converter requires $requiredContentType. Document ${doc.identifier} is ${doc.contentType}")
        }
        return outdocs
    }

    @Override
    List<Document> convert(List<Document> docs) {
        outdocs = []
        for (doc in docs) {
            outdocs << convert(doc)
        }
        return outdocs
    }
}
