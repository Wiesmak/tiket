package pl.umamusume.tiket.printing.serial

import com.fazecast.jSerialComm.SerialPort
import java.io.IOException
import java.io.OutputStream

interface SerialPortSession : AutoCloseable { val output: OutputStream }
fun interface SerialPortProvider { fun open(configuration: SerialPrinterConfiguration): SerialPortSession }

class JSerialCommPortProvider(
    private val resolve: (String) -> SerialPort = { SerialPort.getCommPort(it) }
) : SerialPortProvider {
    override fun open(configuration: SerialPrinterConfiguration): SerialPortSession {
        val port = resolve(configuration.port)
        try {
            if (!port.openPort()) throw IOException("Serial port unavailable")
            if (!port.setComPortParameters(configuration.baud, configuration.dataBits, configuration.stopBits, configuration.parity) ||
                !port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED) ||
                !port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING, 0, configuration.writeTimeoutMillis))
                throw IOException("Serial configuration rejected")
            val stream = port.outputStream
            return object : SerialPortSession {
                override val output = stream
                override fun close() {
                    try { stream.close() } finally { if (!port.closePort()) throw IOException("Serial close failed") }
                }
            }
        } catch (t: Throwable) { port.closePort(); throw t }
    }
}
