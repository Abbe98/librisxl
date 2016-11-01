package whelk.util

import com.google.common.base.Charsets
import org.apache.commons.io.IOUtils
import spock.lang.Specification
import spock.lang.Ignore
import org.codehaus.jackson.map.*
import whelk.Document
import whelk.JsonLd
import whelk.exception.ModelValidationException


class JsonLdSpec extends Specification {

    static final ObjectMapper mapper = new ObjectMapper()

    def "should get id map"() {
        expect:
        JsonLd.getIdMap(['@graph': items]).keySet() == ids as Set
        where:
        ids                     | items
        ['/some', '/other']     | [['@id': '/some'], ['@id': '/other']]
        ['/some', '/other']     | [['@id': '/some'], ['@graph': ['@id': '/other']]]
    }

    def "should frame flat jsonld"() {
        given:
        def id = "1234"
        def documentId = Document.BASE_URI.resolve(id).toString()
        def input = ["@graph": [["@id": documentId, "foo": "bar"]]]
        def expected = ["@id": documentId, "foo": "bar"]
        expect:
        assert JsonLd.frame(id, input) == expected
    }

    def "framing framed jsonld should preserve input"() {
        given:
        def id = "1234"
        def documentId = Document.BASE_URI.resolve(id).toString()
        def input = ["@id": documentId, "foo": "bar"]
        expect:
        assert JsonLd.frame(id, input) == input
    }

    def "should flatten framed jsonld"() {
        given:
        def id = "1234"
        def documentId = Document.BASE_URI.resolve(id).toString()
        def input = ["@id": documentId, "foo": "bar"]
        def expected = ["@graph": [["@id": documentId, "foo": "bar"]]]
        expect:
        assert JsonLd.flatten(input) == expected
    }

    def "flattening flat jsonld should preserve input"() {
        given:
        def id = "1234"
        def documentId = Document.BASE_URI.resolve(id).toString()
        def input = ["@graph": [["@id": documentId, "foo": "bar"]]]
        expect:
        assert JsonLd.flatten(input) == input
    }

    def "should preserve unframed json"() {
        expect:
        JsonLd.flatten(mundaneJson).equals(mundaneJson)
        where:
        mundaneJson = ["data":"foo","sameAs":"/some/other"]
    }

    def "should detect flat jsonld"() {
        given:
        def flatInput = """
        {"@graph": [{"@id": "/bib/13531679",
                     "encLevel": {"@id": "/def/enum/record/AbbreviatedLevel"},
                     "catForm": {"@id":"/def/enum/record/AACR2"},
                     "@type": "Record",
                     "controlNumber": "13531679",
                     "created": "2013-03-02T00:00:00.0+01:00",
                     "catalogingSource": {"@id":"/def/enum/content/OtherSource"},
                     "modified":"2015-09-23T15:20:09.0+02:00",
                     "systemNumber": ["(Elib)9789174771107"],
                     "technicalNote": ["Ändrad av Elib 2013-03-01"],
                     "_marcUncompleted": [{"655": {"ind1": " ",
                                                   "ind2": "4",
                                                   "subfields": [{"a": "E-böcker"}]},
                                           "_unhandled": ["ind2"]},
                                          {"655": {"ind1": " ",
                                                   "ind2": "4",
                                                   "subfields": [{"a":"Unga vuxna"}]},
                                           "_unhandled": ["ind2"]}],
                     "about": {"@id": "/resource/bib/13531679"}}]}
        """
        def framedInput = """
        {"@id": "/bib/13531679",
         "encLevel": {"@id": "/def/enum/record/AbbreviatedLevel"},
         "catForm": {"@id":"/def/enum/record/AACR2"},
         "@type": "Record",
         "controlNumber": "13531679",
         "created": "2013-03-02T00:00:00.0+01:00",
         "catalogingSource": {"@id":"/def/enum/content/OtherSource"},
         "modified":"2015-09-23T15:20:09.0+02:00",
         "systemNumber": ["(Elib)9789174771107"],
         "technicalNote": ["Ändrad av Elib 2013-03-01"],
         "_marcUncompleted": [{"655": {"ind1": " ",
                                       "ind2": "4",
                                       "subfields": [{"a": "E-böcker"}]},
                               "_unhandled": ["ind2"]},
                              {"655": {"ind1": " ",
                                       "ind2": "4",
                                        "subfields": [{"a":"Unga vuxna"}]},
                               "_unhandled": ["ind2"]}],
         "about": {"@id": "/resource/bib/13531679"}}]}
        """
        def flatJson = mapper.readValue(flatInput, Map)
        def framedJson = mapper.readValue(framedInput, Map)
        expect:
        JsonLd.isFlat(flatJson) == true
        JsonLd.isFlat(framedJson) == false
    }

    def  "should retrieve actual URI from @id in document"() {
        when:
        URI uri1 = JsonLd.findRecordURI(
            ["@graph": [["@id": "/qowiudhqw",
                         "name": "foo"]]])
        URI uri2 = JsonLd.findRecordURI(
            ["@graph": [["@id": "http://id.kb.se/foo/bar",
                         "name": "foo"]]])
        URI uri3 = JsonLd.findRecordURI(["data":"foo","sameAs":"/some/other"])
        then:
        uri1.toString() == Document.BASE_URI.toString() + "qowiudhqw"
        uri2.toString() == "http://id.kb.se/foo/bar"
        uri3 == null
    }

    def "should find database id from @id in document"() {
        when:
        String s1 = JsonLd.findIdentifier(
            ["@graph": [["@id": "/qowiudhqw",
                         "name": "foo"]]])
        String s2 = JsonLd.findIdentifier(
            ["@graph": [["@id": "http://id.kb.se/foo/bar",
                         "name": "foo"]]])
        String s3 = JsonLd.findIdentifier(
            ["@graph": [["@id": Document.BASE_URI.resolve("/qowiudhqw").toString(),
                         "name": "foo"]]])
        then:
        s1 == "qowiudhqw"
        s2 == "http://id.kb.se/foo/bar"
        s3 == "qowiudhqw"
    }

    // FIXME This test contains invalid input data
    @Ignore()
    def "should validate item model"() {
        when:
        def id = Document.BASE_URI.resolve("/valid1").toString()
        def mainEntityId = Document.BASE_URI.resolve("/valid1main").toString()
        def validDocument = new Document(
            ["@graph": [["@id": id,
                         "@type": "HeldMaterial",
                         "numberOfItems": 1,
                         "mainEntity": [
                           "@id": mainEntityId
                         ],
                         "heldBy": ["@type":"Organization",
                                    "notation":"Gbg"],
                         "holdingFor": ["@id": "https://libris.kb.se/foobar"]]]
            ])
        def invalidDocument = new Document(["foo": "bar"])

        then:
        assert JsonLd.validateItemModel(validDocument)
        assert !JsonLd.validateItemModel(invalidDocument)
    }

}
