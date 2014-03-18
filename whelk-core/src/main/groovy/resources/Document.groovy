package se.kb.libris.whelks

import groovy.transform.TypeChecked
import groovy.util.logging.Slf4j as Log

import java.io.*
import java.net.URI
import java.util.*
import java.nio.ByteBuffer
import java.lang.annotation.*
import java.security.MessageDigest

import org.codehaus.jackson.*
import org.codehaus.jackson.map.*
import org.codehaus.jackson.annotate.JsonIgnore

import se.kb.libris.whelks.*
import se.kb.libris.whelks.component.*
import se.kb.libris.whelks.exception.*



@Log
class Document {
    String identifier
    byte[] data
    Map entry // For "technical" metadata about the record, such as contentType, timestamp, etc.
    Map meta  // For extra metadata about the object, e.g. links and such.

    @JsonIgnore
    ObjectMapper mapper = new ObjectMapper()

    /*
     * Constructors
     */
    Document() {
        entry = ["timestamp":new Date().getTime()]
        meta = [:]
    }

    Document(String jsonString) {
        fromJson(jsonString)
    }

    Document(File jsonFile) {
        fromJson(jsonFile)
    }

    /*
     * Get methods
     */
    String getDataAsString() {
        return new String(this.data)
    }

    String toJson() {
        return mapper.writeValueAsString(this)
    }

    Map toMap() {
        return mapper.convertValue(this, Map)
    }
    byte[] getData(long offset, long length) {
        byte[] ret = new byte[(int)length]
        System.arraycopy(getData(), (int)offset, ret, 0, (int)length)
        return ret
    }

    String getMetadataAsJson() {
        log.trace("For $identifier. Meta is: $meta, entry is: $entry")
        return mapper.writeValueAsString(["identifier":identifier, "meta":meta, "entry":entry])
    }

    /*
     * Convenience methods
     */
    Document withIdentifier(String i) {
        this.identifier = i
        this.entry['identifier'] = i
        return this
    }
    Document withIdentifier(URI uri) {
        return withIdentifier(uri.toString())
    }

    String getContentType() { entry["contentType"] }

    Document withContentType(String ctype) {
        setContentType(ctype)
        return this
    }

    void setContentType(String ctype) {
        this.entry["contentType"] = ctype
    }

    long getTimestamp() {
        entry.get("timestamp", 0L)
    }

    void setTimestamp(long ts) {
        this.entry["timestamp"] = ts
    }

    void setVersion(int v) {
        this.entry["version"] = v
    }

    int getVersion() {
        entry.get("version", 0)
    }


    List getLinks() {
        return meta.get("links", [])
    }

    Document withTimestamp(long ts) {
        setTimestamp(ts)
        return this
    }

    Document withVersion(int v) {
        setVersion(v)
        return this
    }

    Document withData(String dataString) {
        return withData(dataString.getBytes("UTF-8"))
    }

    Document withData(byte[] data) {
        this.data = data
        calculateChecksum()
        return this
    }
    Document withEntry(Map entrydata) {
        if (entrydata?.get("identifier", null)) {
            this.identifier = entrydata["identifier"]
        }
        if (entrydata != null) {
            this.entry = entrydata
        }
        return this
    }
    Document withMeta(Map metadata) {
        if (metadata != null) {
            this.meta = metadata
        }
        return this
    }

    /**
     * Expects a JSON string containing meta and entry as dictionaries.
     * It's the reverse of getMetadataAsJson().
     */
    Document withMetaEntry(String jsonEntry) {
        Map metaEntry = mapper.readValue(jsonEntry, Map)
        withEntry(metaEntry.entry)
        withMeta(metaEntry.meta)
        return this
    }

    Document withLink(String identifier) {
        if (!meta["links"]) {
            meta["links"] = []
        }
        def link = ["identifier":identifier,"type":""]
        meta["links"] << link
        return this
    }

    Document withLink(String identifier, String type) {
        if (!meta["links"]) {
            meta["links"] = []
        }
        def link = ["identifier":identifier,"type":type]
        meta["links"] << link
        return this
    }

    /**
     * Takes either a String or a File as argument.
     */
    Document fromJson(json) {
        try {
            Document newDoc = mapper.readValue(json, Document)
            this.identifier = newDoc.identifier
            this.data = newDoc.data
            this.entry = newDoc.entry
            this.meta = newDoc.meta
        } catch (JsonParseException jpe) {
            throw new DocumentException(jpe)
        }
        return this
    }

    Document mergeEntry(Map entryData) {
        entryData.each { k, v ->
            if (!this.entry.containsKey(k)
                && k != "deleted"
                && k != "version"
                && k != "contentType"
                && k != "checksum"
                && k != "timestamp") {
                log.info("Setting $k = $v")
                this.entry.put(k, v)
            }
        }
        return this
    }

    private void calculateChecksum() {
        MessageDigest m = MessageDigest.getInstance("MD5")
        m.reset()
        m.update(data)
        byte[] digest = m.digest()
        BigInteger bigInt = new BigInteger(1,digest)
        String hashtext = bigInt.toString(16)
        log.debug("calculated checksum: $hashtext")
        this.entry['checksum'] = hashtext
    }
}

class JsonDocument extends Document {
    Map dataAsMap

    @Override
    void setData(byte[] d) {
        super.setData(d)
        dataAsMap = mapper.convertValue(d, Map)
    }
}
