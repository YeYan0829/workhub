package dev.workhub.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> business(ApiException error) {
        return ResponseEntity.status(error.status).body(ApiError.from(error));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingRequestHeaderException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<ApiError> malformed(Exception error) {
        return business(ApiException.invalid("Invalid request parameters or JSON"));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> oversized(Exception error) {
        return business(new ApiException(413, "CSV_TOO_LARGE", "CSV最大1 MiB，请缩小文件后重试"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiError> database(DataAccessException error) {
        log.error("Database operation failed; transaction rolled back", error);
        return business(
                new ApiException(
                        503,
                        "DATA_ACCESS_ERROR",
                        "Database operation failed; no changes committed"));
    }
}
