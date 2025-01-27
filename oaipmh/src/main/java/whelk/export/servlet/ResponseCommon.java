package whelk.export.servlet;

import org.apache.cxf.staxutils.StaxUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import whelk.Document;
import whelk.JsonLd;
import whelk.Link;
import whelk.util.LegacyIntegrationTools;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.StringReader;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Enumeration;
import java.util.List;

public class ResponseCommon
{
    private static final Logger logger = LogManager.getLogger(ResponseCommon.class);
    private static final XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();

    /**
     * Send a properly formatted OAI-PMH error response to the requesting harvester.
     */
    public static void sendOaiPmhError(String errorCode, String extraMessage, HttpServletRequest request, HttpServletResponse response)
            throws IOException, XMLStreamException
    {
        response.setContentType("text/xml");
        XMLOutputFactory xmlOutputFactory = XMLOutputFactory.newInstance();
        XMLStreamWriter writer = xmlOutputFactory.createXMLStreamWriter(response.getOutputStream());

        // The OAI-PMH specification requires that parameters be echoed in response, unless the response has an error
        // code of badVerb or badArgument, in which case the parameters must be omitted.
        boolean includeParameters = !errorCode.equals(OaiPmh.OAIPMH_ERROR_BAD_VERB) && !errorCode.equals(OaiPmh.OAIPMH_ERROR_BAD_ARGUMENT);

        writeOaiPmhHeader(writer, request, includeParameters);

        writer.writeStartElement("error");
        writer.writeAttribute("code", errorCode);
        writer.writeCharacters(extraMessage);
        writer.writeEndElement();

        writeOaiPmhClose(writer, request);
    }

    /**
     * Send an OAI-PMH error (and return true) if there are any more parameters than the expected ones in the request
     */
    public static boolean errorOnExtraParameters(HttpServletRequest request, HttpServletResponse response, String... expectedParameters)
            throws IOException, XMLStreamException
    {
        String unknownParameters = Helpers.getUnknownParameters(request, expectedParameters);
        if (unknownParameters != null)
        {
            sendOaiPmhError(OaiPmh.OAIPMH_ERROR_BAD_ARGUMENT,
                    "Request contained unknown parameter(s): " + unknownParameters, request, response);
            return true;
        }
        return false;
    }

    public static void writeOaiPmhHeader(XMLStreamWriter writer, HttpServletRequest request, boolean includeParameters)
            throws IOException, XMLStreamException
    {
        // Static header
        writer.writeStartDocument("UTF-8", "1.0");
        writer.writeStartElement("OAI-PMH");
        writer.writeDefaultNamespace("http://www.openarchives.org/OAI/2.0/");

        // Mandatory time element
        writer.writeStartElement("responseDate");
        writer.writeCharacters( ZonedDateTime.now(ZoneOffset.UTC).toString() );
        writer.writeEndElement();

        // Mandatory request element
        writer.writeStartElement("request");
        if (includeParameters)
        {
            Enumeration parameterNames = request.getParameterNames();
            while (parameterNames.hasMoreElements())
            {
                String parameterName = (String) parameterNames.nextElement();
                String parameterValue = request.getParameter(parameterName);
                writer.writeAttribute(parameterName, parameterValue);
            }
        }
        writer.writeCharacters( request.getRequestURL().toString() );
        writer.writeEndElement();
    }

    public static void writeOaiPmhClose(XMLStreamWriter writer, HttpServletRequest req)
            throws IOException, XMLStreamException
    {
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.close();
        logger.info("Response sent successfully to {}:{}.", req.getRemoteAddr(), req.getRemotePort());
    }

    public static void writeConvertedDocument(XMLStreamWriter writer, String formatPrefix, Document jsonLDdoc)
            throws IOException, XMLStreamException
    {
        OaiPmh.FormatDescription formatDescription = OaiPmh.supportedFormats.get(formatPrefix);

        // Convert if the format has a converter (otherwise assume jsonld)
        String convertedText = null;
        if (formatDescription.converter != null)
        {
            try
            {
                convertedText = (String) formatDescription.converter.convert(jsonLDdoc.data, jsonLDdoc.getShortId()).get(JsonLd.getNON_JSON_CONTENT_KEY());
            }
            catch (Exception | Error e) // Depending on the converter, a variety of problems may arise here
            {
                writer.writeCharacters("Error: Document conversion failed.");
                logger.error("Conversion failed for document: " + jsonLDdoc.getShortId(), e);
                return;
            }
        }
        else
            convertedText = jsonLDdoc.getDataAsString();

        // If the format is not XML, it needs to be embedded as CDATA, to not interfere with the response XML format.
        if (formatDescription.isXmlFormat)
            StaxUtils.copy(xmlInputFactory.createXMLStreamReader(new StringReader(convertedText)), writer);
        else
            writer.writeCData(convertedText);
    }

    public static void emitRecord(Document document, XMLStreamWriter writer, String requestedFormat,
                                  boolean onlyIdentifiers, boolean embellish, boolean withDeletedData)
            throws SQLException, XMLStreamException, IOException
    {
        if (embellish)
        {
            document = OaiPmh.s_whelk.loadEmbellished(document.getShortId());
        }

        if (!onlyIdentifiers)
            writer.writeStartElement("record");

        writer.writeStartElement("header");

        if (document.getDeleted())
            writer.writeAttribute("status", "deleted");

        writer.writeStartElement("identifier");
        writer.writeCharacters(document.getURI().toString());
        writer.writeEndElement(); // identifier

        writer.writeStartElement("datestamp");
        writer.writeCharacters(document.getModified());
        writer.writeEndElement(); // datestamp

        String dataset = LegacyIntegrationTools.determineLegacyCollection(document, OaiPmh.s_whelk.getJsonld());
        
        String type = document.getThingType();
        if ( !(dataset.equals("auth") && OaiPmh.workDerivativeTypes.contains(type)))
        {
            writer.writeStartElement("setSpec");
            writer.writeCharacters(dataset);
            writer.writeEndElement(); // setSpec
        }
        
        String sigel = document.getHeldBySigel();
        if (sigel != null)
        {
            writer.writeStartElement("setSpec");
            writer.writeCharacters(dataset + ":" + sigel);
            writer.writeEndElement(); // setSpec
        }

        writer.writeEndElement(); // header

        if (!onlyIdentifiers && (!document.getDeleted() || withDeletedData))
        {
            writer.writeStartElement("metadata");
            ResponseCommon.writeConvertedDocument(writer, requestedFormat, document);
            writer.writeEndElement(); // metadata
        }

        if (!onlyIdentifiers && requestedFormat.contains(OaiPmh.FORMAT_INCLUDE_HOLD_POSTFIX) && dataset.equals("bib"))
        {
            emitAttachedRecords(document, writer, requestedFormat);
        }

        /**
         * Warning: There is a bug here in that <about> should not be visible for ListIdentifiers at all,
         * and certainly not as a sibling of header (which makes it relate to nothing at all),
         * but the export-program along with its modified websök-loading routine now expects this.
         * And so, this must remain broken for the moment being.
         */

        //if (!onlyIdentifiers) {
            writer.writeStartElement("about");

            String itemOf = document.getHoldingFor();
            if (dataset.equals("hold") && itemOf != null) {
                writer.writeStartElement("itemOf");
                writer.writeAttribute("id", itemOf);
                writer.writeEndElement(); // itemOf
            }

            String changedBy = document.getDescriptionLastModifier();
            if (changedBy == null)
                changedBy = "unknown";

            writer.writeStartElement("agent");
            writer.writeAttribute("name", changedBy);
            writer.writeEndElement(); // agent

            writer.writeEndElement(); // about


        if (!onlyIdentifiers)
            writer.writeEndElement(); // record
        //}
    }

    private static void emitAttachedRecords(Document rootDocument, XMLStreamWriter writer, String requestedFormat)
            throws SQLException, XMLStreamException, IOException
    {
        List<Document> holdings = OaiPmh.s_whelk.getAttachedHoldings(rootDocument.getThingIdentifiers());
        writer.writeStartElement("about");
        for (Document holding : holdings)
        {
            String sigel = holding.getHeldBySigel();
            if (sigel == null)
            {
                logger.warn("Holding post without valid sigel! hold id: {}", holding.getShortId());
                continue;
            }

            writer.writeStartElement("holding");
            writer.writeAttribute("sigel", sigel);
            writer.writeAttribute("id", holding.getShortId());
            ResponseCommon.writeConvertedDocument(writer, requestedFormat, holding);
            writer.writeEndElement(); // holding
        }

        for (Link r : JsonLd.getAllReferences(rootDocument.data))
        {
            String ref = r.getIri();
            if (ref.startsWith("https://id.kb.se/") || ref.startsWith(Document.getBASE_URI().toString()))
            {
                String systemID = OaiPmh.s_whelk.getStorage().getSystemIdByIri(ref);
                if (systemID == null)
                    continue;
                if (!OaiPmh.s_whelk.getStorage().getCollectionBySystemID(systemID).equals("auth"))
                    continue;
                Document auth = OaiPmh.s_whelk.loadEmbellished(systemID);

                writer.writeStartElement("auth");
                writer.writeAttribute("id", auth.getShortId());
                ResponseCommon.writeConvertedDocument(writer, requestedFormat, auth);
                writer.writeEndElement(); // auth
            }
        }

        writer.writeEndElement(); // about
    }
}
