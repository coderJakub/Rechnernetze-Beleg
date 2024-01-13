import java.net.DatagramPacket;
import java.util.logging.Level;
import java.io.*;

public class SW extends ARQAbst{
    boolean overflowFlag=false;
    boolean startFlag = true;
    boolean checkCRC = false;
    public SW(Socket socket) {
        super(socket);
    }

    public SW(Socket socket, int sessionID) {
        super(socket, sessionID);
        logger.log(Level.FINEST, "SessionID: "+sessionID);
    }

    @Override
    public void closeConnection() {
        socket.setTimeout(2000);
        logger.log(Level.INFO, "Server-SW: waiting for repeated packets because of lost ACK ");
        DatagramPacket dataPacket; //Datenpaket erstellen

        while(true) {
            try {
                dataPacket = socket.receivePacket();
            } catch (TimeoutException e) {
                logger.log(Level.INFO, "Server-SW: No packet received -> ACK probably arrived -> close Connection");
                return;
            }

	    if(getSessionID(dataPacket)!=sessionID)continue;
            
	    if ((byte)getPacketNr(dataPacket) < this.pNr) {
                logger.log(Level.FINEST, "Server-SW: Out of order-Packet received: " + pNr + " != " + this.pNr);
                sendAck(pNr-1);
            }
        }
    }

    @Override
    public boolean data_req(byte[] hlData, int hlSize, boolean lastTransmission) {
        if(lastTransmission)checkCRC=true;

    //*****generate paket*****

        byte[] sendData = generateDataPacket(hlData, hlSize);
        if(sendData==null){
            logger.log(Level.WARNING, "Client-SW: Fehler bei der Generierung des Datenpakets");
            return false;
        }

    //*****send packet until confirm*****

        int i;
        for(i=0; i<10; i++){
            logger.log(Level.FINEST, "Client-SW: tried send Paket for "+(i+1)+" time, PNR: "+ pNr);
            socket.sendPacket(sendData);
            try {
                if(waitForAck(pNr))break;
            }catch (RuntimeException e){
                logger.log(Level.FINEST, "Client-SW: Packet lost");
            }
        }

    //*****increment packet-number*****

        pNr=(pNr+1)%256;
        if(pNr%128==0)overflowFlag=!overflowFlag;

        if(i==10)logger.log(Level.WARNING, "Client-SW: Maximal retransmission");
        return i<10;
    }

    @Override
    protected boolean waitForAck(int packetNr) {
        socket.setTimeout(1000);
        DatagramPacket ackPacket;

    //****receive packet with right sessionID*****

        do{
            try {
                ackPacket=socket.receivePacket();
            } catch (TimeoutException e) {
                throw new RuntimeException(e);
            }
        }while(getSessionID(ackPacket)!=sessionID);

    //******get CRC from Last ACK paket*******
        if(checkCRC)getAckData(ackPacket);

        return getPacketNr(ackPacket) >=(byte) packetNr; //kumulative ACKs
    }

    @Override
    protected int getPacketNr(DatagramPacket packet) {
        byte[] data = packet.getData();
        return data[2];
    }

    @Override
    protected void getAckData(DatagramPacket packet) {
        ByteArrayInputStream byteDataCRCPacket= new ByteArrayInputStream(packet.getData());
        DataInputStream dataCRCPacket= new DataInputStream(byteDataCRCPacket);

        if(checkCRC){
            try {
                dataCRCPacket.skipBytes(4);
                backData = dataCRCPacket.readInt();
            }catch (IOException e){
                logger.log(Level.WARNING, "Client SW: Fehler beim Auslesen der CRC");
            }
        }
    }
    @Override
    protected int getSessionID(DatagramPacket packet) {
        byte[] data = packet.getData();
        return ((data[0]&0xFF)<<8)|(data[1] & 0xFF);
    }

    @Override
    protected byte[] generateDataPacket(byte[] sendData, int dataSize) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DataOutputStream dataStream = new DataOutputStream(outputStream);

        try {
            dataStream.writeShort(sessionID);
            dataStream.writeByte(pNr);
            dataStream.write(sendData);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Client-SW: Bei der Generierung eines Datenpaketes ist ein Fehler aufgetreten!");
            return null;
        }
        return outputStream.toByteArray();
    }

//************************** SERVER ***************************

    @Override
    public byte[] data_ind_req(int... values) throws TimeoutException {
        if(!startFlag)socket.setTimeout(10000);
        else socket.setTimeout(0);
        DatagramPacket dataPacket;

    //****receive Packet with right packet-number and sessionID (or Start-packet)*****

        do{
            dataPacket = socket.receivePacket();

            if(getPacketNr(dataPacket)!=(byte)pNr){
                logger.log(Level.WARNING, "Server SW: erwartet Paket mit Paktenummer: "+pNr+", bekommt: "+(overflowFlag?getPacketNr(dataPacket)+256:getPacketNr(dataPacket)));
                sendAck(pNr-1); //für kumulative ACKs
            }

        }while ((getSessionID(dataPacket)!=sessionID && !checkStart(dataPacket))||getPacketNr(dataPacket)!=(byte)pNr);

        if(startFlag) {
            sessionID = getSessionID(dataPacket);
            startFlag = false;
        }

    //****send ACK-packet****

        if(values.length!=0)backData=values[0];
        sendAck(pNr);
        logger.log(Level.FINEST, "Sever-SW: Send ACK with packet number: "+(overflowFlag?getPacketNr(dataPacket)+256:getPacketNr(dataPacket)));

    //****increment ACK*****

        pNr=(pNr+1)%256;
        if(pNr%128==0)overflowFlag=!overflowFlag;

    //****get data*******

        int dataLength = dataPacket.getData().length-3;
        byte[] data= new byte[dataLength];

        System.arraycopy(dataPacket.getData(),3, data,0,dataLength);

        logger.log(Level.FINEST, "Server-SW Packet received, SessionNummer: "+sessionID+", Paketnummer: "+pNr+", Datenlänge: "+data.length);

        return data;
    }

    @Override
    byte[] generateAckPacket(int packetNr) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(bos);

        try {
            dos.writeShort((short) sessionID);
            dos.write((byte) packetNr);
            dos.write((byte)1);
            if(backData!=0)dos.writeInt(backData);
            backData=0;

        }catch (IOException e){
            logger.log(Level.WARNING, "Server-SW: Bei der Generierung des ACK Paketes ist ein Fehler aufgetreten!");
            return null;
        }

        return bos.toByteArray();
    }

    @Override
    void sendAck(int nr) {
        byte[] paket = generateAckPacket(nr);
        if(paket != null)socket.sendPacket(paket);
        else logger.log(Level.WARNING, "Server-SW: Bei Senden des ACK Paketes ist ein Fehler aufgetreten!");
    }

    @Override
    boolean checkStart(DatagramPacket packet) {
        byte[] data = packet.getData();
        byte[] start = new byte[5];

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
        DataInputStream dataInputStream = new DataInputStream(byteArrayInputStream);

        try {
            dataInputStream.skipBytes(3);
            dataInputStream.read(start);
        }catch (IOException e){
            logger.log(Level.WARNING, "Server SW: Fehler beim Auslesen der Startkennung!");
            return false;
        }

        return new String(start).equals("Start");
    }
}
