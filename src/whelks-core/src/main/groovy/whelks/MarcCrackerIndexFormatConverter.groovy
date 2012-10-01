package se.kb.libris.whelks.plugin

import se.kb.libris.whelks.*
import se.kb.libris.whelks.exception.*

import groovy.util.logging.Slf4j as Log

import groovy.json.*
import org.codehaus.jackson.map.ObjectMapper

@Log
class MarcCrackerIndexFormatConverter implements IndexFormatConverter {

    String id = this.class.name
    boolean enabled = true
    ObjectMapper mapper
    def marcmap 

    MarcCrackerIndexFormatConverter() { 
        InputStream is = this.getClass().getClassLoader().getResourceAsStream("marcmap.json")
        mapper = new ObjectMapper()
        //this.marcmap = new JsonSlurper().parse(is.newReader())
        this.marcmap = mapper.readValue(is, Map)
        
    }

    def expandField(ctrlfield, columns) {
        def l = [:]
        def propref
        int co = 0
        for (def column : columns) {
            if (propref != column.propRef) {
                l[column.propRef] = ""
            }
            try {
                if (column.length == 1) {
                    l[column.propRef] += ctrlfield[column.offset]
                } else {
                    l[column.propRef] += ctrlfield[(column.offset)..(column.offset+column.length-1)]
                }
            } catch (StringIndexOutOfBoundsException sioobe) {
                l.remove(column.propRef)
                break
            }
            propref = column.propRef
        }
        return l

    }
    

    @Override
    Document convert(Document doc) {
        //log.debug "Start convert on ${doc.dataAsString}"
        def json 
        String d = doc.dataAsString
        try {
            if (d.contains("\\\"")) {
                d = d.replaceAll("\\\"", "/\"")
            }
            //def mapper = new ObjectMapper()
            json = mapper.readValue(doc.dataAsString, Map)

            //json = new JsonSlurper().parseText(doc.dataAsString)
        } catch (Exception e) {
            log.error("Failed to parse document")
            log.error(doc.dataAsString, e)
            return null
        }
        def leader = json.leader 
        def pfx = doc.identifier.toString().split("/")[1]

        def l = expandField(leader, marcmap.get(pfx)."000".fixmaps[0].columns)

        json.leader = ["subfields": l.collect {key, value -> [(key):value]}]

        def mrtbl = l['typeOfRecord'] + l['bibLevel']
        log.trace "Leader extracted"

        json.fields.eachWithIndex() { it, pos ->
            log.trace "Working on json field $pos: $it"
            it.each { fkey, fvalue ->
                if (fkey.startsWith("00")) {
                    if (fkey == "005") {
                        def date
                        try {
                            date = new Date().parse("yyyyMMddHHmmss.S", fvalue)
                        } catch (Exception e) {
                            date = new Date()
                        }
                        json.fields[pos] = [(fkey):date]
                    } else {
                        def matchKey = l['typeOfRecord']
                        if (fkey == "006" || fkey == "007") {
                            matchKey = fvalue[0]
                        }
                        marcmap.get(pfx).each { key, value ->
                            if (fkey == key) {
                                try {
                                    value.fixmaps.each { fm ->
                                        if ((!fm.matchRecTypeBibLevel && fm.matchKeys.contains(matchKey)) || (fm.matchRecTypeBibLevel &&  fm.matchRecTypeBibLevel.contains(mrtbl))) {
                                            if (fkey == "008" && fvalue.length() == 39) {
                                                log.warn("Document ${doc.identifier} has wrong length in 008")
                                                    fvalue = fvalue[0..19] + "|" + fvalue[20..-1]
                                            }
                                            json.fields[pos] = [(fkey):["subfields": expandField(fvalue, fm.columns).collect {k, v -> [(k):v] } ]]
                                        }
                                    }
                                } catch (groovy.lang.MissingPropertyException mpe) { 
                                    log.warn("Exception in $fm : ${mpe.message}")
                                } catch (Exception e) {
                                    log.error("Document identifier: ${doc.identifier}")
                                        log.error("fkey: $fkey")
                                        log.error("l: $l")
                                        throw e
                                }

                            }
                        }
                    }
                }
            }
        }


        try {
            def builder = new JsonBuilder(json)
            doc.withData(builder.toString())
        } catch (Exception e) {
            log.error("Failed to create cracked marc index: ${e.message}")
            log.error("JSON structure: $json")
            throw new se.kb.libris.whelks.exception.WhelkRuntimeException(e)
        }

        return doc
    }


    void enable() { this.enabled = true }
    void disable() { this.enabled = false }
}
