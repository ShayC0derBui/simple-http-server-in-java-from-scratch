package response;

public enum ContentType {
    TEXT_PLAIN("text/plain"),
    APPLICATION_OCTET_STREAM("application/octet-stream");

    private final String mimeType;

    ContentType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getMimeType() {
        return mimeType;
    }
}