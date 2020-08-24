package whelk.component

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListenableFutureTask
import com.google.common.util.concurrent.ThreadFactoryBuilder
import groovy.util.logging.Log4j2 as Log
import whelk.Document
import whelk.Link
import whelk.exception.MissingMainIriException
import whelk.util.Metrics

import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import java.util.function.Supplier

@Log
class DependencyCache {
    private static final int CACHE_SIZE = 10_000
    private static final int REFRESH_INTERVAL_MINUTES = 5

    PostgreSQLComponent storage

    private Executor cacheRefresher = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setDaemon(true).build())

    private LoadingCache<Link, Set<String>> dependersCache = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .refreshAfterWrite(REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .recordStats()
            .build(loader(storage.&getDependersOfType))

    private LoadingCache<Link, Set<String>> dependenciesCache = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .refreshAfterWrite(REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES)
            .recordStats()
            .build(loader(storage.&getDependenciesOfType))
    
    DependencyCache(PostgreSQLComponent storage) {
        this.storage = storage

        Metrics.cacheMetrics.addCache('dependersCache', dependersCache)
        Metrics.cacheMetrics.addCache('dependencyCache', dependenciesCache)
    }

    Set<String> getDependenciesOfType(String iri, String typeOfRelation) {
        return dependenciesCache.getUnchecked(new Link(iri: iri, relation: typeOfRelation))
    }

    Set<String> getDependersOfType(String iri, String typeOfRelation) {
        return dependersCache.getUnchecked(new Link(iri: iri, relation: typeOfRelation))
    }

    void invalidate(Document doc) {
        String docIri = doc.getThingIdentifiers().first()
        doc.getExternalRefs().each { Link link ->
            dependersCache.invalidate(link)
            dependenciesCache.invalidate(new Link(iri: docIri, relation: link.relation))
        }
    }

    private CacheLoader<Link, Set<String>> loader(BiFunction<String, String, Set> func) {
        return new CacheLoader<Link, Set<String>>() {
            @Override
            Set<String> load(Link link) {
                try {
                    def iris = func.apply(storage.getSystemIdByThingId(link.iri), link.relation)
                            .collect(storage.&getThingMainIriBySystemId)

                    return iris.isEmpty()
                            ? Collections.EMPTY_SET
                            : Collections.unmodifiableSet(new HashSet(iris))
                }
                catch (MissingMainIriException e) {
                    log.warn("Missing Main IRI: $e")
                    return Collections.EMPTY_SET
                }
            }

            @Override
            ListenableFuture<Set<String>> reload(Link key, Set<String> oldValue) throws Exception {
                return reloadTask( { load(key) } )
            }
        }
    }

    private <V> ListenableFutureTask<V> reloadTask(Supplier<V> reloadFunction) {
        ListenableFutureTask<V> task = ListenableFutureTask.create(new Callable<V>() {
            @Override
            V call() throws Exception {
                return reloadFunction.get()
            }
        })

        cacheRefresher.execute(task)
        return task
    }
}
