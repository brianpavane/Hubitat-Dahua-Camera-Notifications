package groovyx.net.http

class HttpResponseException extends RuntimeException {
    final int statusCode
    final Object response

    HttpResponseException(int statusCode, Object response = null, String message = null) {
        super(message ?: "HTTP ${statusCode}")
        this.statusCode = statusCode
        this.response = response
    }
}
