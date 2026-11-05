package com.unicanal.channel;

import com.unicanal.common.ApiException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;

/**
 * Errores al enviar por un canal, distinguiendo si el mensaje pudo haber salido:
 * <ul>
 *   <li>{@link #FAILED}: el canal respondió con error o la conexión ni siquiera se abrió. Seguro que no salió.</li>
 *   <li>{@link #UNCONFIRMED}: la petición se envió pero no hubo respuesta (timeout, conexión cortada a mitad).
 *       El cliente pudo haberlo recibido: reintentar puede duplicarlo, así que la interfaz pide confirmación.</li>
 * </ul>
 */
public final class ChannelSendErrors {

    public static final String FAILED = "CHANNEL_SEND_FAILED";
    public static final String UNCONFIRMED = "CHANNEL_SEND_UNCONFIRMED";
    public static final String UNCONFIRMED_REASON =
            "No se pudo confirmar el envío; puede que el cliente sí lo haya recibido";

    private ChannelSendErrors() {
    }

    public static ApiException unconfirmed() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, UNCONFIRMED, UNCONFIRMED_REASON);
    }

    /** La conexión no llegó a abrirse: la petición nunca salió del servidor. */
    public static boolean neverConnected(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ConnectException || current instanceof UnknownHostException
                    || current instanceof NoRouteToHostException || current instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Error de red o de tiempo sin respuesta del canal, después de haber podido enviar la petición. */
    public static boolean isUnconfirmed(Throwable error) {
        if (neverConnected(error)) {
            return false;
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof IOException || current instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }
}
