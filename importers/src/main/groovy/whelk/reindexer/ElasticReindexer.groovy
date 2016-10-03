package whelk.reindexer

import whelk.Document
import whelk.Whelk
import whelk.util.Tools

/**
 * Created by markus on 2015-12-10.
 */
class ElasticReindexer {

    static final int BATCH_SIZE = 5000
    Whelk whelk

    ElasticReindexer(Whelk w) {
        this.whelk = w
    }


    void reindex(String collection) {
        int counter = 0
        long startTime = System.currentTimeMillis()
        List<Document> documents = []
        for (document in whelk.storage.loadAll(collection)) {
            documents.add(document)
            Tools.printSpinner("Elapsed time: ${(System.currentTimeMillis() - startTime)/1000} seconds. Loaded $counter documents.", counter)
            counter++
            if (counter % BATCH_SIZE == 0) {
                long indexTime = System.currentTimeMillis()
                print("Elapsed time: ${(System.currentTimeMillis() - startTime)/1000} seconds. Loaded $counter documents. Bulk indexing ${documents.size()} documents ...")
                whelk.elastic.bulkIndex(documents, collection)
                println(" In ${(System.currentTimeMillis()-indexTime)} milliseconds.")
                documents = []
            }
        }
        if (documents.size() > 0) {
            whelk.elastic.bulkIndex(documents, collection)
        }
        println("Done! $counter documents reindexed in ${(System.currentTimeMillis() - startTime)/1000} seconds.")
    }
}
