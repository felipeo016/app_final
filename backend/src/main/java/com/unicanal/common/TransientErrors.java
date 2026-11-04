package com.unicanal.common;

import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * Distingue los errores pasajeros (base de datos caída, timeout, red) de los permanentes (datos que
 * nunca se van a poder procesar). Solo los pasajeros justifican pedir un reintento: repetir un error
 * de datos solo lo repetiría.
 */
public final class TransientErrors {

    private TransientErrors() {
    }

    /** true si la excepción, o alguna de sus causas, es un fallo de infraestructura que puede resolverse solo. */
    public static boolean isTransient(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof RetryableException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof TransientDataAccessException
                    || current instanceof RecoverableDataAccessException
                    || current instanceof MongoSocketException
                    || current instanceof MongoTimeoutException
                    || current instanceof MongoExecutionTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof IOException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /** Error propio que debe tratarse como pasajero (p. ej. configuración que puede corregirse sin perder el evento). */
    public static class RetryableException extends RuntimeException {

        public RetryableException(String message) {
            super(message);
        }
    }
}
