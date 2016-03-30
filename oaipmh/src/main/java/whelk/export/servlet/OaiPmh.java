package whelk.export.servlet;

import whelk.converter.FormatConverter;
import whelk.converter.JsonLD2DublinCoreConverter;
import whelk.converter.JsonLD2RdfXml;
import whelk.converter.marc.JsonLD2MarcXMLConverter;
import whelk.util.PropertyLoader;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.sql.*;
import java.util.HashMap;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OaiPmh extends HttpServlet
{
    // OAI-PMH Error/Condition codes
    public final static String OAIPMH_ERROR_BAD_VERB = "badVerb";
    public final static String OAIPMH_ERROR_BAD_RESUMPTION_TOKEN = "badResumptionToken";
    public final static String OAIPMH_ERROR_BAD_ARGUMENT = "badArgument";
    public final static String OAIPMH_ERROR_CANNOT_DISSEMINATE_FORMAT = "cannotDisseminateFormat";
    public final static String OAIPMH_ERROR_ID_DOES_NOT_EXIST = "idDoesNotExist";
    public final static String OAIPMH_ERROR_NO_RECORDS_MATCH = "noRecordsMatch";
    public final static String OAIPMH_ERROR_NO_METADATA_FORMATS = "noMetadataFormats";
    public final static String OAIPMH_ERROR_NO_SET_HIERARCHY = "noSetHierarchy";

    // Supported OAI-PMH metadata formats
    public static class FormatDescription
    {
        public FormatDescription(FormatConverter converter, boolean isXmlFormat, String xmlSchema, String xmlNamespace) {
            this.converter = converter;
            this.isXmlFormat = isXmlFormat;
            this.xmlSchema = xmlSchema;
            this.xmlNamespace = xmlNamespace;
        }
        public final FormatConverter converter;
        public final boolean isXmlFormat;
        public final String xmlSchema;
        public final String xmlNamespace;
    }
    public final static HashMap<String, FormatDescription> supportedFormats;
    public final static String FORMATEXPANDED_POSTFIX = "_expanded";
    static
    {
        supportedFormats = new HashMap<String, FormatDescription>();
        supportedFormats.put("oai_dc", new FormatDescription(new JsonLD2DublinCoreConverter(), true, "http://www.openarchives.org/OAI/2.0/oai_dc.xsd", "http://www.openarchives.org/OAI/2.0"));
        supportedFormats.put("marcxml", new FormatDescription(new JsonLD2MarcXMLConverter(), true, "http://www.loc.gov/standards/marcxml/schema/MARC21slim.xsd", "http://www.loc.gov/MARC21/slim"));
        supportedFormats.put("rdfxml", new FormatDescription(new JsonLD2RdfXml(), true, null, null));
        supportedFormats.put("jsonld", new FormatDescription(null, false, null, null));

        // For each format add another format with the :expanded postfix and the same parameters
        HashMap<String, FormatDescription> expandedFormats = new HashMap<String, FormatDescription>();
        for (String formatKey : supportedFormats.keySet())
        {
            expandedFormats.put(formatKey+FORMATEXPANDED_POSTFIX, supportedFormats.get(formatKey));
        }
        supportedFormats.putAll(expandedFormats);
    }

    public static Properties configuration;
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException
    {
        handleRequest(req, res);
    }

    public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException
    {
        handleRequest(req, res);
    }

    public void init()
    {
        configuration = PropertyLoader.loadProperties("secret");
        DataBase.init();
    }

    public void destroy()
    {
        DataBase.destroy();
    }

    private void handleRequest(HttpServletRequest req, HttpServletResponse res) throws IOException
    {
        String verb = req.getParameter("verb");
        if (verb == null)
            verb = "";

        logger.info("Received request with verb: {} from {}:{}.", verb, req.getRemoteAddr(), req.getRemotePort());

        res.setContentType("text/xml");

        try
        {
            switch (verb) {
                case "GetRecord":
                    GetRecord.handleGetRecordRequest(req, res);
                    break;
                case "Identify":
                    Identify.handleIdentifyRequest(req, res);
                    break;
                case "ListIdentifiers":
                    // ListIdentifiers is (just about) identical to ListRecords, except that metadata bodies are omitted
                    ListRecords.handleListRecordsRequest(req, res, true);
                    break;
                case "ListMetadataFormats":
                    ListMetadataFormats.handleListMetadataFormatsRequest(req, res);
                    break;
                case "ListRecords":
                    ListRecords.handleListRecordsRequest(req, res, false);
                    break;
                case "ListSets":
                    ListSets.handleListSetsRequest(req, res);
                    break;
                default:
                    ResponseCommon.sendOaiPmhError(OAIPMH_ERROR_BAD_VERB, "OAI-PMH verb must be one of [GetRecord, Identify, " +
                            "ListIdentifiers, ListMetadataFormats, ListRecords, ListSets].", req, res);
            }
        }
        catch (IOException | XMLStreamException e)
        {
            // These exceptions are to be expected in every case where a client/harvester closes or loses connection
            // while a response is being sent.
            logger.info("Broken client pipe {}:{}, response feed interrupted.", req.getRemoteAddr(), req.getRemotePort(), e);
        }
        catch (SQLException e)
        {
            res.sendError(500);
            logger.error("Database error.", e);
        }
    }
}
