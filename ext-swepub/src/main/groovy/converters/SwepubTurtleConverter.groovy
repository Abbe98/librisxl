package se.kb.libris.whelks.swepub.plugin

import groovy.util.logging.Slf4j as Log

import se.kb.libris.whelks.*
import se.kb.libris.whelks.basic.*
import se.kb.libris.whelks.plugin.*

@Log
class SwepubTurtleConverter extends BasicRDFFormatConverter {

    String requiredContentType = "application/mods+xml"

    @Override
    List<RDFDescription> doConvert(Document doc) {
        return null
    }
}
