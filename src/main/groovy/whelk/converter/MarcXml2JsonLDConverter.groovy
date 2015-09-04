package whelk.converter

import groovy.xml.StreamingMarkupBuilder
import groovy.util.slurpersupport.GPathResult
import groovy.util.logging.Slf4j as Log

import whelk.*
import se.kb.libris.util.marc.*
import se.kb.libris.util.marc.io.*
import whelk.converter.marc.MarcFrameConverter

@Log
class MarcXml2JsonLDConverter {
    String requiredContentType = "application/marcxml+xml"
    String resultContentType = "application/ld+json"

    MarcFrameConverter marcFrameConverter = null

    Document doConvert(final Document document) {
        if (marcFrameConverter == null) {
            marcFrameConverter = plugins.find { it instanceof MarcFrameConverter }
        }
        assert marcFrameConverter

        MarcRecord record = MarcXmlRecordReader.fromXml(document.dataAsString)

        log.debug("Creating new document ${document.identifier} from doc with entry: ${document.entry} and meta: ${document.meta}")

        Document doc = marcFrameConverter.doConvert(record, ["entry": document.entry, "meta": document.meta])

        log.debug("Document ${doc.identifier} created successfully with entry: ${doc.entry} and meta: ${doc.meta}")

        return doc.withContentType(resultContentType)
    }
}
