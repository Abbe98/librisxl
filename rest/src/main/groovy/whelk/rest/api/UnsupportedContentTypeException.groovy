package whelk.rest.api

/**
 * Created by markus on 2015-10-15.
 */
class UnsupportedContentTypeException extends RuntimeException {
    public UnsupportedContentTypeException(String msg) {
        super(msg)
    }
}
