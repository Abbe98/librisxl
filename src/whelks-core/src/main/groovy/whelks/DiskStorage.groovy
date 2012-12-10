package se.kb.libris.whelks.component

import groovy.util.logging.Slf4j as Log

import se.kb.libris.whelks.*
import se.kb.libris.whelks.basic.*
import se.kb.libris.whelks.exception.*
import se.kb.libris.whelks.plugin.*

@Log
class DiskStorage extends BasicPlugin implements Storage {
    def storageDir = "./storage"
    def whelkPrefix
    boolean enabled = true

    String id = "diskstorage"
    String docFolder = "_"
    Map<URI, Long> inventory

    int PATH_CHUNKS=4
    final String INVENTORY_FILE = "inventory.data"

    DiskStorage(String directoryName, String wlkPfx) {
        StringBuilder dn = new StringBuilder(directoryName)
        while (dn[dn.length()-1] == '/') {
            dn.deleteCharAt(dn.length()-1)
        }
        this.storageDir = dn.toString() // + "/" + whelkPrefix
        this.whelkPrefix = wlkPfx
        /*
        try {
            File invFile = new File(this.storageDir + "/" + INVENTORY_FILE).withObjectInputStream { instream ->
                instream.eachObject {
                    this.inventory = it
                }
            }
            log.info("Loading inventory.")
        } catch (Exception e) {
            this.inventory = new HashMap<URI, Long>()
            log.info("Creating inventory.")
        }
        */

        log.info("Starting DiskStorage with storageDir $storageDir")
    }

    def void enable() {this.enabled = true}
    def void disable() {this.enabled = false}

    OutputStream getOutputStreamFor(Document doc) {
        File file = new File(buildPath(doc.identifier))
        return file.newOutputStream()
    }

    @Override
    LookupResult lookup(Key key) {
        throw new UnsupportedOperationException("Not supported yet.")
    }

    @Override
    Document get(URI uri) {
        File f = new File(buildPath(uri, false))
        try {
            def document = new BasicDocument(f.text)
            log.debug("Loading document from disk.")
            return document
        } catch (FileNotFoundException fnfe) {
            return null
        }
    }

    @Override
    Iterable<Document> getAll() {
        def baseDir = new File(this.storageDir+"/"+this.whelkPrefix)
        return new DiskDocumentIterable(baseDir)
    }

    @Override
    void store(Document doc, boolean saveInventory = true) {
        File file = new File(buildPath(doc.identifier, true))
        file.write(doc.toJson())
        this.inventory[doc.identifier] = doc.timestamp
        /*
        if (saveInventory) {
            log.debug("Saving inventory")
            new File(this.storageDir + "/" + INVENTORY_FILE).withObjectOutputStream {
                it << this.inventory
            }
        }
        */
    }

    @Override
    void store(Iterable<Document> docs) {
        docs.each {
            store(it, false)
        }
        /*
        log.debug("Saving inventory")
        new File(this.storageDir + "/" + INVENTORY_FILE).withObjectOutputStream {
            it << this.inventory
        }
        */
    }

    void updateInventory() {
        for (doc in getAll()) {
            inventory[doc.identifier] = doc.timestamp
        }
        log.debug("Saving inventory")
        new File(this.storageDir + "/" + INVENTORY_FILE).withObjectOutputStream {
            it << this.inventory
        }
    }

    @Override
    void delete(URI uri) {
        try {
            if (!new File(buildPath(uri, false)).delete()) {
                log.error("Failed to delete $uri")
            }
        } catch (Exception e) {
            throw new WhelkRuntimeException(e)
        }
    }

    String buildPath(URI id, boolean createDirectories) {
        def path = this.storageDir + "/" + id.toString().substring(0, id.toString().lastIndexOf("/"))
        def basename = id.toString().substring(id.toString().lastIndexOf("/")+1)

        for (int i=0; i*PATH_CHUNKS+PATH_CHUNKS < basename.length(); i++) {
            path = path + "/" + basename[i*PATH_CHUNKS .. i*PATH_CHUNKS+PATH_CHUNKS-1].replaceAll(/[\.]/, "")
        }

        if (this.docFolder) {
            path = path + "/" + this.docFolder
        }
        if (createDirectories) {
            new File(path).mkdirs()
        }
        return path.replaceAll(/\/+/, "/") + "/" + basename
    }

    class DiskDocumentIterable implements Iterable<Document> {
        File baseDirectory
        DiskDocumentIterable(File bd) {
            this.baseDirectory = bd

        }

        Iterator<Document> iterator() {
            return new DiskDocumentIterator(this.baseDirectory)
        }
    }

    class DiskDocumentIterator<Document> implements Iterator {

        private LinkedList<File> fileStack = new LinkedList<File>()
        private Queue<Document> resultQueue = new LinkedList<Document>()

        DiskDocumentIterator(File startDirectory) {
            fileStack.addAll(startDirectory.listFiles())
        }

        public boolean hasNext() {
            if (resultQueue.isEmpty()) {
                populateResults();
            }
            return !resultQueue.isEmpty();
        }

        public Document next() {
            if (resultQueue.isEmpty()) {
                populateResults();
            }
            return resultQueue.poll();
        }


        private void populateResults() {

            while (!fileStack.isEmpty() && resultQueue.isEmpty()) {
                File currentFile = fileStack.pop();

                if (currentFile.isFile() && currentFile.length() > 0) {
                    def d = new BasicDocument(currentFile)
                    resultQueue.offer(d)
                }

                if (currentFile.isDirectory()) {
                    fileStack.addAll(Arrays.asList(currentFile.listFiles()))
                }
            }
        }

        public void remove() {}

    }

    static main(args) {
        new DiskStorage("/extra/whelk_storage", args[0]).updateInventory()
    }
}
