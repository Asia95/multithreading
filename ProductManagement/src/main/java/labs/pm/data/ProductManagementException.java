package labs.pm.data;

public class ProductManagementException extends Exception {

    public ProductManagementException() {
        super();
    }

    public ProductManagementException(String message) {
        super(message);
    }

    public ProductManagementException(String message, Throwable cause) {
        super(message, cause);
    }
}
