package pl.umamusume.tiket.backend

import com.fazecast.jSerialComm.SerialPort
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.umamusume.tiket.backend.application.*
import pl.umamusume.tiket.printing.*
import pl.umamusume.tiket.printing.console.ConsoleTicketPrinter
import pl.umamusume.tiket.printing.serial.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SerialPrintingTests {
    @Test fun realPortProviderParametersAndClose() {
        val port = mock(SerialPort::class.java)
        val out = ByteArrayOutputStream()
        `when`(port.openPort()).thenReturn(true)
        `when`(port.setComPortParameters(9600,8,1,0)).thenReturn(true)
        `when`(port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)).thenReturn(true)
        `when`(port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING,0,5000)).thenReturn(true)
        `when`(port.outputStream).thenReturn(out)
        `when`(port.closePort()).thenReturn(true)
        val provider = JSerialCommPortProvider { name -> assertEquals("COM9", name); port }
        provider.open(SerialPrinterConfiguration("COM9")).use { it.output.write(42) }
        verify(port).setComPortParameters(9600,8,1,0)
        verify(port).setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING,0,5000)
        verify(port).closePort()
        assertContentEquals(byteArrayOf(42), out.toByteArray())
    }

    @Test fun failedOpenAndStreamAcquisitionClosePort() {
        for (opened in listOf(false, true)) {
            val port = mock(SerialPort::class.java)
            `when`(port.openPort()).thenReturn(opened)
            `when`(port.setComPortParameters(9600,8,1,0)).thenReturn(true)
            `when`(port.setFlowControl(0)).thenReturn(true)
            `when`(port.setComPortTimeouts(SerialPort.TIMEOUT_WRITE_BLOCKING,0,5000)).thenReturn(true)
            `when`(port.outputStream).thenThrow(IllegalStateException("offline"))
            assertFails { JSerialCommPortProvider { port }.open(SerialPrinterConfiguration("COM9")) }
            verify(port).closePort()
        }
    }

    @Test fun lanesRunTogetherDoNotQueueAndStopClaims() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val coordinator = SerialDispatchCoordinator(listOf(setOf(1,3), setOf(2))) {
            calls.incrementAndGet(); entered.countDown()
            assertTrue(release.await(10,TimeUnit.SECONDS))
        }
        try {
            coordinator.poll()
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            repeat(20) { coordinator.poll() }
            assertEquals(2, calls.get())
        } finally { release.countDown(); coordinator.close() }
        coordinator.poll()
        assertEquals(2, calls.get())
    }

    private fun runner() = ApplicationContextRunner().withUserConfiguration(
        PrintingConfiguration::class.java, SerialPrintingConfiguration::class.java, PrintingBackendValidation::class.java)

    @Test fun consoleIsDefaultAndSerialBindsWithoutNativeAccess() {
        runner().run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBean(TicketPrinter::class.java) is ConsoleTicketPrinter)
        }
        runner().withPropertyValues("tiket.printing.backend=serial", "tiket.printing.lease-seconds=300",
            "tiket.printing.serial.routes[0].pos=1", "tiket.printing.serial.routes[0].port=COM9").run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBean(TicketPrinter::class.java) is SerialTicketPrinter)
            assertEquals(listOf(setOf(1)), context.getBean(SerialRoutes::class.java).lanes)
        }
    }

    @Test fun invalidSerialNeverFallsBack() {
        runner().withPropertyValues("tiket.printing.backend=serial","tiket.printing.lease-seconds=300").run {
            assertNotNull(it.startupFailure)
        }
        runner().withPropertyValues("tiket.printing.backend=invalid").run { assertNotNull(it.startupFailure) }
        assertFailsWith<IllegalArgumentException> {
            SerialPrintingProperties().apply {
                routes = listOf(SerialPrintingProperties.Route().apply { pos=1;port="COM9" },
                    SerialPrintingProperties.Route().apply { pos=1;port="COM10" })
            }.validated()
        }
    }

    @Test fun exactlyOneWorkerOrNoneWhenDisabled() {
        fun workers() = runner().withUserConfiguration(PrintWorker::class.java, SerialPrintWorker::class.java)
            .withBean(PrintOutbox::class.java, java.util.function.Supplier { mock(PrintOutbox::class.java) })
            .withBean(org.springframework.jdbc.core.JdbcTemplate::class.java,
                java.util.function.Supplier { mock(org.springframework.jdbc.core.JdbcTemplate::class.java) })
            .withBean(pl.umamusume.tiket.backend.persistence.PersistenceTransactions::class.java,
                java.util.function.Supplier { mock(pl.umamusume.tiket.backend.persistence.PersistenceTransactions::class.java) })
        workers().run {
            assertNull(it.startupFailure)
            assertEquals(1, it.getBeansOfType(PrintWorker::class.java).size)
            assertTrue(it.getBeansOfType(SerialPrintWorker::class.java).isEmpty())
        }
        val serial = workers().withPropertyValues("tiket.printing.backend=serial","tiket.printing.lease-seconds=300",
            "tiket.printing.serial.routes[0].pos=1","tiket.printing.serial.routes[0].port=COM9")
        serial.run {
            assertNull(it.startupFailure)
            assertTrue(it.getBeansOfType(PrintWorker::class.java).isEmpty())
            assertEquals(1, it.getBeansOfType(SerialPrintWorker::class.java).size)
        }
        serial.withPropertyValues("tiket.printing.enabled=false").run {
            assertNull(it.startupFailure)
            assertTrue(it.getBeansOfType(PrintWorker::class.java).isEmpty())
            assertTrue(it.getBeansOfType(SerialPrintWorker::class.java).isEmpty())
        }
    }
}
