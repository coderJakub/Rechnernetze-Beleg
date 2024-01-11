import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** @author Jörg Vogt */

/*
 * Remarks: UDP-checksum calculation, UDP-Lite RFC 3828
 * UDP checksum is calculated over IP-Pseudo-Header, UDP-Header, UDP-Data
 * No option to disable checksum in JAVA for UDP
 * UDP-Lite is part of Linux-kernel since 2.6.20
 * UDP-Lite support in java not clear
 */

public class FileCopy {
  static int port;
  static int delay;
  static double loss;
  static String dir = "upload/";

  public static void main(String[] args) throws IOException {
    Logger logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
    CustomLoggingHandler.prepareLogger(logger);
    /* set logging level
     * Level.CONFIG: default information (incl. RTSP requests)
     * Level.ALL: debugging information (headers, received packages and so on)
     */
    logger.setLevel(Level.CONFIG);
    logger.setLevel(Level.INFO);

    // CHANGE: Check args.length & args[0] in one go to avoid further bugs
    // e.g. starting client with 2 arguments only or exit without message for unknown args[0]
    if ((args.length == 4 || args.length == 5) && args[0].equals("client")) {
      String host = args[1];
      port = Integer.parseInt(args[2]);
      String fileName = args[3];
      // CHANGE: Fix ArrayIndexOutOfBoundsException when 5th argument is omitted
      // CHANGE: Rename arqProt to windowSize & convert it to int right away
      int windowSize = (args.length == 5) ? Integer.parseInt(args[4]) : 1;
      System.out.println("Client started for connection to: " + host + " at port " + port);
      System.out.println("Window size: " + windowSize);
      sendFile(host, port, fileName, windowSize);

    } else if ((args.length == 2 || args.length == 4) && args[0].equals("server")) {
      port = Integer.parseInt(args[1]);
      if (args.length == 4) {
        loss = Double.parseDouble(args[2]);
        delay = Integer.parseInt(args[3]);
      }
      System.out.println("Server started at port: " + port);
      handleConnection(port);

    } else {
      System.out.println("Usage: FileCopy server port [loss] [delay]");
      // CHANGE: mark 5th argument as optional & rename from protocol
      System.out.println("Usage: FileCopy client host port file [window]");
      System.exit(1);
    }
  }

  // CHANGE: Rename arq to windowSize & use int instead of String
  private static void sendFile(String host, int port, String fileName, int windowSize)
      throws IOException {
    // establish socket - exception possible
    // TODO Exception handling
    Socket socket = new Socket(host, port);
    FileTransfer myFT = new FileTransfer(host, socket, fileName, windowSize);
    boolean c = myFT.file_req();
    if (c) System.out.println("Client-AW: Ready");
    else {
      System.out.println("Client-AW: Abort because of maximum retransmission");
      System.exit(1);
    }
  }

  private static void handleConnection(int port) throws IOException {
    // establish connection
    Socket socket = new Socket(port, loss, delay);
    FileTransfer myFT = new FileTransfer(socket, dir);
    do {
      if (myFT.file_init() ) System.out.println("Server-AW: file received");
      else System.out.println("Server-AW: file receive abort (time out)");
    } while (true);
  }
}
