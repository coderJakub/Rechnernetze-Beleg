import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Random;
import java.io.*;
import java.util.zip.CRC32;
import java.util.Timer;
import java.util.TimerTask;

public class FileTransfer implements  FT{
    private ARQ mARQ;
    private Logger logger;
    private String host;
    private Socket socket;
    private String fileName;
    private int arq;
    private File file;
    private long fileLength;
    private String dir;

    public FileTransfer(String host, Socket socket, String fileName, int arq) {
        this.host = host;
        this.socket = socket;
        this.fileName = fileName;
        this.arq = arq;
        this.file = new File(fileName);

        mARQ = new SW(socket, generateSessionID());
        logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        logger.log(Level.FINEST, "Client_FT Constructor");
    }

    public FileTransfer(Socket socket, String dir) {
        mARQ = new SW(socket, generateSessionID());
        this.dir = dir;
        this.socket = socket;
        logger = Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        logger.log(Level.FINEST, "Server_FT Constructor");
    }

    public class DataTransferProgressTracker extends TimerTask {
        private int totalDataSent;
        private final int fileSize;
        private final Timer timer;
        private int dataInSec;

        public DataTransferProgressTracker(int fileSize) {
            this.fileSize = fileSize;
            this.totalDataSent = 0;
            this.timer = new Timer();
            this.dataInSec=0;
        }

        @Override
        public void run() {
            dataInSec++;
            double percentage = ((double) totalDataSent / fileSize) * 100;
            String formattedOutput = String.format("Progress: %.2f %%, Date-rate: %.2f KBit/s, Total time: %d s", percentage, (double)(totalDataSent/dataInSec)/1024*8, dataInSec);
            logger.log(Level.INFO, formattedOutput);
        }

        public void startTracking() {
            timer.schedule(this, 1000, 1000); // Schedule the task to run every second after an initial delay of 1 second
        }

        public void stopTracking() {
            timer.cancel();
        }

        public void updateDataSent(int dataSent) {
            this.totalDataSent += dataSent;
        }
    }

    @Override
    public boolean file_req() throws IOException {
        DataTransferProgressTracker progressTracker = new DataTransferProgressTracker((int) file.length());
        byte[] startPacket;

        byte[] fileBuffer;
        int dataPointer = 0;
        FileInputStream fileInputStream;
        File file = new File(fileName);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);

        long startTime = System.currentTimeMillis();
        long endTime;
        long totalTime;
        double dataRate;
        double dataRateInKbps;

        progressTracker.startTracking();
        if(!file.exists()){
            logger.log(Level.WARNING, "Client-FT: Die angegebene Datei konnte nicht gefunden werden");
            return false;
        }
        fileInputStream = new FileInputStream(fileName);

    //****send start packet******

        startPacket =  generateStartPacket();
        if(!mARQ.data_req(startPacket, startPacket.length, false))return  false;
        progressTracker.updateDataSent(startPacket.length);
        logger.log(Level.FINEST, "Client_FT Send Start-paket");

    //*****read data from file*****

        fileBuffer = new byte[fileInputStream.available()];
        fileInputStream.read(fileBuffer);
        fileInputStream.close();

    //*****send data packets*****

        while(dataPointer < file.length()) {

            dos.write(fileBuffer, dataPointer, dataPointer+1400<file.length()?1400:(int)file.length()-dataPointer);
            dataPointer += 1400;

            if (!mARQ.data_req(bos.toByteArray(), 1400, false)) return false;

            progressTracker.updateDataSent(dataPointer+1400<file.length()?1400:(int)file.length()-dataPointer);
            bos.reset();
        }

    //*****send CRC********

        //_____create CRC and send______

        CRC32 crc32=new CRC32();
        crc32.update(fileBuffer);
        dos.writeInt((int)crc32.getValue());

        if(!mARQ.data_req(bos.toByteArray(), 4, true))return false;

        //_____compare CRC_____

        int CRCFromServer = mARQ.getBackData();
        if(CRCFromServer!=(int)crc32.getValue()) {
            logger.log(Level.WARNING, "Client FT: Die Datei wurde nicht richtig übertragen, bitte erneut ausführen");
            logger.log(Level.FINER, "Server FT: CRC Client: "+ crc32.getValue());
            logger.log(Level.FINER, "Server FT: CRC Server: "+ CRCFromServer);
            return false;
        }
        logger.log(Level.INFO, "Die Datei wurde erfolgreich übertragen!");

    //*****show data-rate*****

        progressTracker.stopTracking();

        endTime = System.currentTimeMillis();
        totalTime = endTime - startTime;
        dataRate = ((double) file.length() / totalTime) * 1000;
        dataRateInKbps = dataRate / 1024;

        String formattedOutputTotalData = String.format(" %.2f KB", ((double)file.length())/1024);
        String formattedOutputTotalTime = String.format(" %.2f s", ((double)totalTime)/1000);
        String formattedOutputDataRate = String.format(" %.2f KBit/s", dataRateInKbps*8);

        logger.log(Level.INFO, "\nTotal data transferred:" + formattedOutputTotalData);
        logger.log(Level.INFO, "Total time taken:" + formattedOutputTotalTime);
        logger.log(Level.INFO, "Data transfer rate:" + formattedOutputDataRate + "\n");

        return true;
    }

    @Override
    public boolean file_init() throws IOException {
        mARQ = new SW(socket, generateSessionID());

        byte[] startPacketData;
        byte[] startIdentifier = new byte[5];
        short fileNameLength;
        byte[] fileNameBytes;
        int CRCFromClient;

        byte[] filteredData;
        byte[] dataPacketData;

        byte[] CRCPacketData;
        ByteArrayOutputStream fileByteDataStream = new ByteArrayOutputStream();
        ByteArrayInputStream ARQByteDataInputStream;
        DataInputStream ARQDataInputStream;

        logger.log(Level.INFO, "\n***Server FT: Waiting for new Connection!***\n");

    //************Start Packet*************

        try {
            startPacketData = mARQ.data_ind_req();
        } catch (TimeoutException e) {
            logger.log(Level.WARNING, "Sever FT: Timeout");
            return false;
        }

        //______get file details_____

        ARQByteDataInputStream = new ByteArrayInputStream(startPacketData);
        ARQDataInputStream = new DataInputStream(ARQByteDataInputStream);

        ARQDataInputStream.read(startIdentifier);
        if (!new String(startIdentifier).equals("Start"))return false; //Kann eigentlich nicht passieren

        fileLength = ARQDataInputStream.readLong();

        fileNameLength = ARQDataInputStream.readShort();

        fileNameBytes = new byte[fileNameLength];
        ARQDataInputStream.read(fileNameBytes);
        fileName = new String(fileNameBytes);

        //______check CRC______

        CRCFromClient = ARQDataInputStream.readInt();
        CRC32 crc = new CRC32();
        crc.update(startPacketData, 0, 5+8+2+fileNameLength);
        if((int)crc.getValue()!=CRCFromClient) {
            logger.log(Level.WARNING, "Server FT: Startpaket wurde nicht richtig übertragen -> Abbruch");
            logger.log(Level.FINER, "Server FT: CRC Server: "+ crc.getValue());
            logger.log(Level.FINER, "Server FT: CRC Client: "+ CRCFromClient);
            return false;
        }
        else logger.log(Level.INFO, "Server FT: Startpaket erfolgreich übertragen: "+fileLength/1000+"KB werden erwartet");

    //************Data Packets*************

        for(int i=0; i<fileLength; i+=1400){
            try{
                dataPacketData=mARQ.data_ind_req();
            }catch (Exception e){
                logger.log(Level.WARNING, "Sever FT: Timeout");
                return false;
            }

            ARQByteDataInputStream = new ByteArrayInputStream(dataPacketData);
            ARQDataInputStream = new DataInputStream(ARQByteDataInputStream);

            if(i+1400<fileLength)filteredData = new byte[1400];
            else filteredData = new byte[(int)fileLength%1400];

            ARQDataInputStream.read(filteredData);
            logger.log(Level.FINEST, "Server FT: Daten der Größe "+filteredData.length+" angekommen");
            fileByteDataStream.write(filteredData);
        }

    //************CRC Packets*************

        //_____create CRC and send______

        crc = new CRC32();
        crc.update(fileByteDataStream.toByteArray());
        try {
            CRCPacketData = mARQ.data_ind_req((int)crc.getValue());
        }catch (Exception e){
            logger.log(Level.WARNING, "Sever FT: Timeout");
            return false;
        }

        //_____compare CRC_____

        ARQByteDataInputStream = new ByteArrayInputStream(CRCPacketData);
        ARQDataInputStream = new DataInputStream(ARQByteDataInputStream);

        CRCFromClient = ARQDataInputStream.readInt();
        if((int)crc.getValue()!=CRCFromClient){
            logger.log(Level.WARNING, "Server FT: Die Datei wurde nicht richtig übertragen -> Abbruch!");
            return false;
        }
        logger.log(Level.INFO, "Server FT:Daten wurde erfolgreich übertragen!");

    //************Close Connection*************

        //______make dir if not exists_____

        File downloadDir = new File(dir);
        if(!downloadDir.exists() || !downloadDir.isDirectory())downloadDir.mkdir();

        //______create new File______

        File file = new File(dir+fileName);
        for(int i=1; file.exists(); i++)file= new File(dir+fileName.substring(0, fileName.lastIndexOf('.'))+"("+i+")"+"."+fileName.split("\\.")[fileName.split("\\.").length - 1]);

        //______write data in file______

        FileOutputStream fos = new FileOutputStream(file);
        fos.write(fileByteDataStream.toByteArray());
        fos.close();

        //______close ARQ connection________

        mARQ.closeConnection();
        return true;
    }
    
    private int generateSessionID(){
        Random rand = new Random();
        return rand.nextInt(65536);
    }
    private byte[] generateStartPacket(){
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(outputStream);
        byte[] filenameBytes = file.getName().getBytes(StandardCharsets.UTF_8);

        try {
            dataStream.writeBytes("Start"); //5 Byte Startkennung
            dataStream.writeLong(file.length()); //64 Bit Dateilänge
            dataStream.writeShort(filenameBytes.length); //16 Bit Länge des Dateinamens
            dataStream.write(filenameBytes); //0-255 Byte Dateiname

            CRC32 crc = new CRC32();
            crc.update(outputStream.toByteArray());

            dataStream.writeInt((int)crc.getValue());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Client FT: Bei der Generierung des Startpaketes ist ein Fehler aufgetreten!");
            System.exit(-1);
        }
        return outputStream.toByteArray();
    }
}
