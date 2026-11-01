package tn.hydra.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.PersistenceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.security.SecureRandom;
import java.util.NoSuchElementException;
import org.hibernate.LazyInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.FileStorageException;
import tn.hydra.exception.ResourceNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** reference: only for unexpected errors (INTERNAL_ERROR), also written in the log. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String code, String message, String reference) {
        public ApiError(String code, String message) { this(code, message, null); }
    }

    private static final String REF_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> businessRule(BusinessRuleException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(org.hibernate.LazyInitializationException.class)
    public ResponseEntity<ApiError> lazyInitialization(LazyInitializationException e,
                                                       HttpServletRequest request) {
        return internalError(e, request, "LazyInitializationException (session JPA fermée)");
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ApiError> fileStorage(FileStorageException e, HttpServletRequest request) {
        if ("FILE_NOT_FOUND".equals(e.getCode())) {
            log.warn("File not found on {} {}: {}", method(request), uri(request), e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiError(e.getCode(), e.getMessage()));
        }
        return internalError(e, request, "FileStorage failure (code=" + e.getCode() + ")");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("FILE_TOO_LARGE", "Fichier trop volumineux"));
    }

    // Method security denies must stay 403; without this the generic handler below
    // would turn them into 500.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("FORBIDDEN", "Vous n'avez pas cette permission"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException e) {
        String msg = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        log.warn("Data integrity violation: {}", msg);
        // Common case: unique constraint on (document_id, rev_label) or FK violation
        if (msg != null && (msg.contains("unique") || msg.contains("duplicate") || msg.contains("rev_label"))) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("REV_EXISTS", "Cette révision existe déjà"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("DATA_INTEGRITY", "Violation de contrainte de données"));
    }

    /**
     * Catches Hibernate/JPA persistence failures that escape the @Transactional boundary.
     * Common causes on Supabase / Vercel Postgres:
     *   - Row Level Security enabled with no policies → INSERT/SELECT denied
     *   - Missing sequence (doc_reference_seq) → relation does not exist
     *   - FK violation on document.folder_id or document.created_by
     */
    @ExceptionHandler(PersistenceException.class)
    public ResponseEntity<ApiError> persistence(PersistenceException e, HttpServletRequest request) {
        return internalError(e, request, "[DB] PersistenceException");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<ApiError> malformed(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST", "Requête invalide"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> missingPart(MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("MISSING_PART",
                        "Champ de fichier manquant dans la requête multipart : " + e.getRequestPartName()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(
            MissingServletRequestParameterException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("MISSING_PARAMETER",
                        "Paramètre requis manquant : " + e.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        String name = e.getName() != null ? e.getName() : "paramètre";
        String value = e.getValue() != null ? String.valueOf(e.getValue()) : "";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("INVALID_PARAMETER",
                        "Valeur invalide pour le paramètre " + name + " : " + value));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> noSuchElement(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND",
                        // "No value present" = JDK default of Optional.orElseThrow(): not user text
                        e.getMessage() != null && !"No value present".equals(e.getMessage())
                                ? e.getMessage() : "Ressource introuvable"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> illegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST",
                        e.getMessage() != null ? e.getMessage() : "Argument invalide"));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraintViolation(ConstraintViolationException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("VALIDATION_FAILED", "Violation de contrainte de validation"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception e, HttpServletRequest request) {
        return internalError(e, request, "Unhandled exception");
    }

    /**
     * Unexpected error: 500 INTERNAL_ERROR with a short reference, never a class name,
     * stack trace, SQL or package name in the body. The full exception is logged at
     * ERROR level with the same reference, so support can find it from the user's toast.
     */
    private ResponseEntity<ApiError> internalError(Throwable e, HttpServletRequest request, String what) {
        String ref = newReference();
        Throwable root = rootCause(e);
        log.error("[{}] {} on {} {} — type={} rootType={} rootMsg={}",
                ref, what, method(request), uri(request),
                e.getClass().getName(), root.getClass().getName(), root.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError("INTERNAL_ERROR",
                        "Une erreur technique est survenue. Référence : " + ref, ref));
    }

    /** 6 characters A-Z / 0-9. */
    static String newReference() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(REF_CHARS.charAt(RANDOM.nextInt(REF_CHARS.length())));
        return sb.toString();
    }

    // request can be null when a handler is invoked programmatically (unit tests).
    private static String method(HttpServletRequest request) { return request == null ? "?" : request.getMethod(); }
    private static String uri(HttpServletRequest request) { return request == null ? "?" : request.getRequestURI(); }

    /** Walk the cause chain to find the deepest root cause. */
    private static Throwable rootCause(Throwable t) {
        Throwable cause = t.getCause();
        return (cause == null || cause == t) ? t : rootCause(cause);
    }
}