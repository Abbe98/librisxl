package whelk;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
//import org.json.simple.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

public class AuthenticationFilter implements Filter {

    private static final ObjectMapper mapper = new ObjectMapper();
    private List<String> supportedMethods;
    private List<String> filterOnPorts;
    private String url = null;

    final static Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

        String initParams = filterConfig.getInitParameter("supportedMethods");
        String filterOnPortsStr = filterConfig.getInitParameter("filterOnPorts");

        supportedMethods = splitInitParameters(initParams);
        filterOnPorts = splitInitParameters(filterOnPortsStr);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;
        if (isApiCall(httpRequest) && supportedMethods != null && supportedMethods.contains(httpRequest.getMethod())) {
            String json = null;
            try {
                String token = httpRequest.getHeader("Authorization");
                if (token == null) {
                    httpResponse.sendError(httpResponse.SC_UNAUTHORIZED, "Invalid accesstoken, Token is: "+token);
                    return;
                }
                json = verifyToken(token.replace("Bearer ", ""));
                if (json == null || json.isEmpty()) {
                    httpResponse.sendError(httpResponse.SC_UNAUTHORIZED, "The access token expired");
                    return;
                }

                HashMap result = mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
                if (!isExpired(Long.parseLong(result.get("exp").toString()))) {
                    request.setAttribute("user", result.get("user"));
                    chain.doFilter(request, response);
                }else {
                    httpResponse.sendError(httpResponse.SC_UNAUTHORIZED);
                }
            } catch (org.codehaus.jackson.JsonParseException jpe) {
                log.error("JsonParseException. Failed to parse:" + json, jpe);
                httpResponse.sendError(httpResponse.SC_INTERNAL_SERVER_ERROR);
            } catch (Exception e) {
                httpResponse.sendError(httpResponse.SC_INTERNAL_SERVER_ERROR);
                e.printStackTrace();
            }
        } else if (supportedMethods != null && supportedMethods.contains(httpRequest.getMethod())) {
            log.debug("Authentication check bypassed, creating dummy user.");
            request.setAttribute("user", createDevelopmentUser());
            chain.doFilter(request, response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private Map createDevelopmentUser() {
        Map emptyUser = new HashMap<String,Object>();
        emptyUser.put("user", "SYSTEM");
        return emptyUser;
    }

    private String verifyToken(String token) {

        try {

            HttpClient client = HttpClientBuilder.create().build();
            HttpGet get = new HttpGet(getVerifyUrl());

            get.setHeader("Authorization", "Bearer " + token);
            HttpResponse response = client.execute(get);

            BufferedReader rd = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent()));

            StringBuffer result = new StringBuffer();
            String line = "";
            while ((line = rd.readLine()) != null) {
                result.append(line);
            }

            rd.close();
            return result.toString();

        }catch (Exception e) {
            e.printStackTrace();
        }

        return null;

    }

    @Override
    public void destroy() {

    }

    private boolean isApiCall(HttpServletRequest httpRequest) {
        return filterOnPorts.contains(new Integer(httpRequest.getServerPort()).toString());
    }

    /*
    private JSONObject getUserInfo(JSONObject obj) {

        if (obj != null) {
            return (JSONObject)obj.get("user");
        }
        return null;
    }
    */

    private boolean isExpired(long unixtime) {
        Date now = new Date();
        Date expires = new Date(unixtime);
        return now.compareTo(expires) > 0;
    }

    /**
     *
     * @param initParams
     * @return a list with all supported methods
     */
    private List<String> splitInitParameters(String initParams) {

        if (initParams != null) {
            return Arrays.asList(initParams.replace(" ", "").split(","));
        }
        return null;
    }

    private String getVerifyUrl() {
        if (url == null) {
            Map secrets = null;
            try {
                secrets = mapper.readValue(this.getClass().getClassLoader().getResourceAsStream("secrets.json"), new TypeReference<Map<String, Object>>() {});
            } catch (IOException ioe) {
                throw new RuntimeException("Failed to load api properties.", ioe);
            }
            url = (String)secrets.get("oauth2verifyurl");
        }
        return url;
    }
}
