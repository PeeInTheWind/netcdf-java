/*
 * Copyright 1997-2008 Unidata Program Center/University Corporation for
 * Atmospheric Research, P.O. Box 3000, Boulder, CO 80307,
 * support@unidata.ucar.edu.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or (at
 * your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package ucar.nc2.iosp.bufr;

import ucar.unidata.io.RandomAccessFile;
import ucar.unidata.io.KMPMatch;

import java.io.*;
import java.nio.channels.WritableByteChannel;

/**
 * Sequentially scans a BUFR file, extracts the messages.
 * @author caron
 * @since May 9, 2008
 */
public class MessageScanner {
  //static public final int MAX_MESSAGE_SIZE = 15000; // see http://www.weather.gov/tg/tablea.html
  static public final int MAX_MESSAGE_SIZE = 500 * 1000; // GTS allows up to 500 Kb messages (ref?)
  static private org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MessageScanner.class);

  static private final KMPMatch matcher = new KMPMatch("BUFR".getBytes());

  /**
   * is this a valid BUFR file.
   *
   * @param raf check this file
   * @return true if its a BUFR file
   * @throws IOException on read error
   */
  static public boolean isValidFile(ucar.unidata.io.RandomAccessFile raf) throws IOException {
    raf.seek(0);
    if (!raf.searchForward(matcher, 8000)) return false; // must find "BUFR" in first 8k
    raf.skipBytes(4);
    BufrIndicatorSection is = new BufrIndicatorSection(raf);
    if (is.getBufrEdition() > 4) return false;
    // if(is.getBufrLength() > MAX_MESSAGE_SIZE) return false;
    if (is.getBufrLength() > raf.length()) return false;
    return true;
  }

  /////////////////////////////////

  private ucar.unidata.io.RandomAccessFile raf = null;
  //private BufrMessage first = null;
  private int countMsgs = 0;
  private int countObs = 0;
  private byte[] header;
  private long startPos = 0;
  private long lastPos = 0;
  private long nbytes = 0;

  public MessageScanner(RandomAccessFile raf) throws IOException {
    this(raf, 0);
  }

  public MessageScanner(RandomAccessFile raf, long startPos) throws IOException {
    this.raf = raf;
    raf.seek(startPos);
    raf.order(RandomAccessFile.BIG_ENDIAN);
  }

  public boolean hasNext() throws IOException {
    if (lastPos >= raf.length()) return false;
    raf.seek(lastPos);
    boolean more = raf.searchForward(matcher, -1); // will scan to end for another BUFR header
    if (more) {
      long stop = raf.getFilePointer();
      int sizeHeader = (int) (stop - lastPos);
      if (sizeHeader > 30) sizeHeader = 30;
      header = new byte[sizeHeader];
      startPos = stop-sizeHeader;
      raf.seek(startPos);
      raf.read(header);
    }
    // System.out.println(" more "+more+" at "+startPos+" lastPos "+ lastPos+" nbytes= "+nbytes+ " msg "+countMsgs);
    return more;
  }

  public Message next() throws IOException {
    long start = raf.getFilePointer();
    raf.seek(start + 4);

    BufrIndicatorSection is = new BufrIndicatorSection(raf);
    BufrIdentificationSection ids = new BufrIdentificationSection(raf, is);
    BufrDataDescriptionSection dds = new BufrDataDescriptionSection(raf);

    long dataPos = raf.getFilePointer();
    int dataLength = BufrNumbers.uint3(raf);
    BufrDataSection dataSection = new BufrDataSection(dataPos, dataLength);
    lastPos = dataPos + dataLength + 4; // position to the end message plus 1
    nbytes +=  lastPos - startPos;

    // length consistency checks
    if (is.getBufrLength() > MAX_MESSAGE_SIZE) {
      log.warn("Illegal length - BUFR message at pos "+start+" header= "+header);
      return null;
    }

    if (is.getBufrEdition() > 4) {
      log.warn("Illegal edition - BUFR message at pos " + start + " header= " + cleanup(header));
      return null;
    }

    if (is.getBufrEdition() < 2) {
      log.warn("Edition "+ is.getBufrEdition()+" is not supported - BUFR message at pos " + start + " header= " +cleanup(header));
      return null;
    }

    // check that end section is correct
    long ending = dataPos + dataLength;
    raf.seek(dataPos + dataLength);
    for (int i = 0; i < 3; i++) {
      if (raf.read() != 55) {
        log.warn("Missing End of BUFR message at pos=" + ending + " header= " + cleanup(header));
        return null;
      }
    }
    // allow off by one : may happen when dataLength rounded to even bytes
    if (raf.read() != 55) {
      raf.seek(dataPos + dataLength-1); // see if byte before is a '7'
      if (raf.read() != 55) {
        log.warn("Missing End of BUFR message at pos=" +ending+ " header= " + cleanup(header)+" edition= "+is.getBufrEdition());
        return null;
      } else {
        log.warn("End of BUFR message off-by-one at pos= " +ending+ " header= " + cleanup(header)+" edition= "+is.getBufrEdition());
        lastPos--;
      }
    }

    Message m = new Message(raf, is, ids, dds, dataSection);
    m.setHeader( cleanup(header));
    m.setStartPos( start);

    countMsgs++;
    countObs += dds.getNumberDatasets();
    raf.seek(start + is.getBufrLength());
    return m;
  }

  public byte[] getMessageBytesFromLast(Message m) throws IOException {
    long startPos = m.getStartPos();
    int length = (int) (lastPos - startPos);
    byte[] result = new byte[length];

    raf.seek(startPos);
    raf.readFully(result);
    return result;
  }

  public byte[] getMessageBytes(Message m) throws IOException {
    long startPos = m.getStartPos();
    int length = (int) m.is.getBufrLength();
    byte[] result = new byte[length];

    raf.seek(startPos);
    raf.readFully(result);
    return result;
  }

  public int getTotalObs() {
    return countObs;
  }

  public int getTotalMessages() {
    return countMsgs;
  }

    // the WMO header is in here somewhere when the message comes over the IDD
  private static String cleanup(byte[] h) {
    byte[] bb = new byte[h.length];
    int count = 0;
    for (byte b : h) {
      if (b >= 32 && b < 127)
        bb[count++] = b;
    }
    return new String(bb, 0, count);
  }

  public long writeCurrentMessage( WritableByteChannel out) throws IOException {
    long nbytes = lastPos - startPos;
    return  raf.readToByteChannel(out, startPos, nbytes);
  }

}
