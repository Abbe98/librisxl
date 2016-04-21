package whelk.importer

import org.codehaus.jackson.map.ObjectMapper
import whelk.Document
import whelk.IdGenerator
import whelk.Whelk

/**
 * Created by markus on 2015-12-10.
 */
class DefinitionsImporter extends Importer {

    static final ObjectMapper mapper = new ObjectMapper()

    String definitionsFilename

    DefinitionsImporter(Whelk w) {
        whelk = w
    }

    void doImport(String collection) {
        long startTime = System.currentTimeMillis()
        File defFile = new File(definitionsFilename)
        List documentList = []
        int counter = 0
        defFile.eachLine {
            def data = mapper.readValue(it.getBytes("UTF-8"), Map)
            def newId = IdGenerator.generate()
            Document doc = new Document(newId, data).withContentType("application/ld+json").inCollection(collection)
            documentList.add(doc)
            counter++
        }
        println("Created $counter documents from $definitionsFilename in ${(System.currentTimeMillis()-startTime)/1000} seconds. Now storing to system.")
        whelk.bulkStore(documentList)
        println("Operation complete. Time elapsed: ${(System.currentTimeMillis() - startTime)/1000} seconds.")
    }
}
