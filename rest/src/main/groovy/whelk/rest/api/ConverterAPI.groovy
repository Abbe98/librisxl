package whelk.rest.api

import groovy.util.logging.Slf4j as Log

import org.apache.http.entity.ContentType
import whelk.Whelk
import whelk.Document
import whelk.util.Tools
import whelk.converter.marc.MarcFrameConverter
import whelk.util.PropertyLoader

import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@Log
class ConverterAPI extends HttpServlet {

    MarcFrameConverter marcFrameConverter

    public ConverterAPI() {
        log.info("Starting converterAPI ...")
        marcFrameConverter = new MarcFrameConverter()
        log.info("Started ...")
    }

    @Override
    void doGet(HttpServletRequest request, HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED)
    }

    @Override
    void doPost(HttpServletRequest request, HttpServletResponse response) {
        String ctype = ContentType.parse(request.getContentType()).getMimeType()
        if (ctype != "application/ld+json") {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                    "Received data in unexpected format: ${ctype}")
            return
        }
        if (request.getContentLength() == 0) {
            log.warn("Received no content to reformat.")
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No content received.")
            return
        }

        String requestedContentType = request.getParameter("to")
        // TODO: getHeader("Accepts"); ContentType.parse(...) ...

        if (!requestedContentType || requestedContentType == "application/x-marcjson") {
            String jsonText = Tools.normalizeString(request.getInputStream().getText("UTF-8"))
            Map json = marcFrameConverter.mapper.readValue(jsonText, Map)
            log.info("Constructed document. Converting to $requestedContentType")
            json = marcFrameConverter.runRevert(json)
            def framedText = marcFrameConverter.mapper.writeValueAsString(json)
            HttpTools.sendResponse(response, framedText, "application/ld+json")
        }
        else if (requestedContentType) {
            def msg = "Can not convert to $requestedContentType."
            log.info(msg)
            response.sendError(HttpServletResponse.SC_NOT_ACCEPTABLE , msg)
        } else {
            def msg = "No conversion requested."
            log.info(msg)
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, msg)
        }
    }

}
