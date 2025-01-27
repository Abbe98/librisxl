package whelk.rest.api

import javax.servlet.http.HttpServletRequest

import static whelk.rest.api.CrudUtils.*

class CrudGetRequest {
    private HttpServletRequest request
    private String resourceId
    private String contentType
    private View view
    private Lens lens

    static CrudGetRequest parse(HttpServletRequest request) {
        return new CrudGetRequest(request)
    }

    private CrudGetRequest(HttpServletRequest request) {
        this.request = request
        parsePath(getPath())
        contentType = getBestContentType(request)
        lens = parseLens(request)
    }

    HttpServletRequest getHttpServletRequest() {
        return request
    }

    String getPath() {
        return Crud.getRequestPath(request)
    }

    String getId() {
        return resourceId
    }

    Optional<String> getVersion() {
        return Optional.ofNullable(request.getParameter("version"))
    }

    Optional<ETag> getIfNoneMatch() {
        getIfNoneMatch(request)
    }

    String getContentType() {
        return contentType
    }

    boolean shouldEmbellish() {
        if (getVersion().present) {
            return false
        }

        return getBoolParameter("embellished").orElse(true)
    }

    boolean shouldFrame() {
        return getBoolParameter("framed").orElse(contentType == MimeTypes.JSON)
    }

    boolean shouldApplyInverseOf() {
        return getBoolParameter("_applyInverseOf").orElse(false)
    }

    View getView() {
        return view
    }

    Lens getLens() {
        return lens
    }

    /**
     * Parse a CRUD path
     *
     * Matches /<id>, /<id>/<view> and /<id>/<view>.<suffix>
     * where view is 'data' or 'data-view'
     */
    private void parsePath(String path) {
        def matcher = path =~ ~/^\/(.+?)(\/(data|data-view|_changesets)(\.(\w+))?)?$/
        if (matcher.matches()) {
            resourceId = matcher[0][1]
            view = View.fromString(matcher[0][3])
        } else {
            throw new Crud.NotFoundException("Not found:" + path)
        }
    }

    private Lens parseLens(HttpServletRequest request) {
        String lens = request.getParameter('lens')
        if (lens == null) {
            return Lens.NONE
        }
        try {
            return Lens.valueOf(lens.toUpperCase())
        }
        catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown lens:" + lens)
        }
    }

    private Optional<Boolean> getBoolParameter(String name) {
        return Optional
                .ofNullable(request.getParameter(name) ?: request.getAttribute(name))
                .map(Boolean.&parseBoolean)
    }

    enum View {
        RESOURCE(''),
        DATA('data'),
        DATA_VIEW('data-view'),
        CHANGE_SETS('_changesets');

        private String name

        View(String name) {
            this.name = name
        }

        static View fromString(s) {
            for (View v : values()) {
                if (v.name == s) {
                    return v
                }
            }
            return RESOURCE
        }
    }

    enum Lens {
        CHIP, CARD, NONE
    }
}
