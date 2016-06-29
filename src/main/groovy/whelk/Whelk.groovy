package whelk

import groovy.util.logging.Slf4j as Log
import org.picocontainer.Characteristics
import org.picocontainer.DefaultPicoContainer
import org.picocontainer.containers.PropertiesPicoContainer
import whelk.component.Index
import whelk.component.Storage
import whelk.filter.JsonLdLinkExpander
import whelk.util.PropertyLoader

/**
 * Created by markus on 15-09-03.
 */
@Log
class Whelk {

    Storage storage
    Index elastic
    JsonLdLinkExpander expander
    String version

    public Whelk(String version, Storage pg, Index es, JsonLdLinkExpander ex) {
        this.storage = pg
        this.elastic = es
        this.expander = ex
        this.version = version
        log.info("Whelk started with storage ${storage}, index $elastic and expander.")
    }

    public Whelk(String version, Storage pg, Index es) {
        this.storage = pg
        this.elastic = es
        this.version = version
        log.info("Whelk started with storage $storage and index $elastic")
    }

    public Whelk(String version, Storage pg) {
        this.storage = pg
        this.version = version
        log.info("Whelk started with storage $storage")
    }

    public Whelk() {
    }

    public static DefaultPicoContainer getPreparedComponentsContainer(Properties properties) {
        DefaultPicoContainer pico = new DefaultPicoContainer(new PropertiesPicoContainer(properties))
        Properties componentProperties = PropertyLoader.loadProperties("component")
        for (comProp in componentProperties) {
            if (comProp.key.endsWith("Class") && comProp.value && comProp.value != "null") {
                println("Adding pico component ${comProp.key} = ${comProp.value}")
                pico.as(Characteristics.CACHE, Characteristics.USE_NAMES).addComponent(Class.forName(comProp.value))
            }
        }
        pico.as(Characteristics.CACHE, Characteristics.USE_NAMES).addComponent(Whelk.class)
        return pico
    }

    Document store(Document document, String changedIn, String changedBy, boolean createOrUpdate = true) {
        if (storage.store(document, createOrUpdate, changedIn, changedBy)) {
            if (elastic) {
                elastic.index(document)
            }
        }
        return document
    }

    void bulkStore(final List<Document> documents, String changedIn, String changedBy, boolean createOrUpdate = true) {
        if (storage.bulkStore(documents, createOrUpdate, changedIn, changedBy)) {
            if (elastic) {
                elastic.bulkIndex(documents)
            }
        } else {
            log.warn("Bulk store failed, not indexing : ${documents.first().id} - ${documents.last().id}")
        }
    }

    void remove(String id) {
        if (storage.remove(id)) {
            if (elastic) {
                elastic.remove(id)
            }
        }
    }
}
