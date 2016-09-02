package org.dkf.jed2k;

import org.dkf.jed2k.data.PieceBlock;
import org.dkf.jed2k.exception.ErrorCode;
import org.dkf.jed2k.exception.JED2KException;
import org.dkf.jed2k.protocol.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.LinkedList;

/**
 * Created by inkpot on 15.07.2016.
 * executes write data into file on disk
 */
public class PieceManager extends BlocksEnumerator {
    private String filepath;
    private RandomAccessFile file;
    private FileChannel channel;
    private LinkedList<BlockManager> blockMgrs = new LinkedList<BlockManager>();
    private Logger log = LoggerFactory.getLogger(PieceManager.class);

    public PieceManager(String filepath, int pieceCount, int blocksInLastPiece) {
        super(pieceCount, blocksInLastPiece);
        this.filepath = filepath;
    }

    /**
     * create or open file on disk
     */
    private void open() throws JED2KException {
        if (file == null) {
            try {
                file = new RandomAccessFile(filepath, "rw");
                channel = file.getChannel();
            }
            catch(FileNotFoundException e) {
                throw new JED2KException(ErrorCode.FILE_NOT_FOUND);
            }
            catch(SecurityException e) {
                throw new JED2KException(ErrorCode.SECURITY_EXCEPTION);
            }
        }
    }

    private BlockManager getBlockManager(int piece) {
        for(BlockManager mgr: blockMgrs) {
            if (mgr.getPieceIndex() == piece) return mgr;
        }

        blockMgrs.addLast(new BlockManager(piece, blocksInPiece(piece)));
        return blockMgrs.getLast();
    }

    /**
     * actual write data to file
     * @param b block
     * @param buffer data source
     */
    public LinkedList<ByteBuffer> writeBlock(PieceBlock b, final ByteBuffer buffer) throws JED2KException {
        open();
        assert(file != null);
        assert(channel != null);
        long bytesOffset = b.blocksOffset()* Constants.BLOCK_SIZE;
        BlockManager mgr = getBlockManager(b.pieceIndex);
        assert(mgr != null);

        // TODO - add error handling here with correct buffer return to requester
        try {
            // stage 1 - write block to disk, possibly error occurred
            // buffer must have remaining data
            assert(buffer.hasRemaining());
            channel.position(bytesOffset);
            while(buffer.hasRemaining()) channel.write(buffer);
            buffer.rewind();
        }
        catch(IOException e) {
            throw new JED2KException(ErrorCode.IO_EXCEPTION);
        }

        // stage 2 - prepare hash and return obsolete blocks if possible
        return mgr.registerBlock(b.pieceBlock, buffer);
    }

    /**
     * restore block in piece managers after application restart
     * works like write block method but reads data from file to buffer
     *
     * @param b piece block of data
     * @param buffer buffer from common session pool as memory for operation
     * @param fileSize size of file associated with transfer
     * @return free buffers
     * @throws JED2KException
     */
    public LinkedList<ByteBuffer> restoreBlock(PieceBlock b, ByteBuffer buffer, long  fileSize) throws JED2KException {
        open();
        assert(file != null);
        assert(channel != null);
        assert(fileSize > 0);

        long bytesOffset = b.blocksOffset()*Constants.BLOCK_SIZE;
        BlockManager mgr = getBlockManager(b.pieceIndex);

        // prepare buffer for reading from file
        buffer.clear();
        buffer.limit(b.size(fileSize));

        try {
            // read data from file to buffer
            channel.position(bytesOffset);
            while(buffer.hasRemaining()) channel.read(buffer);
            buffer.flip();
        }
        catch(IOException e) {
            throw new JED2KException(ErrorCode.IO_EXCEPTION);
        }

        // register buffer as usual in blocks manager and return free blocks
        assert(buffer.remaining() == b.size(fileSize));
        return mgr.registerBlock(b.pieceBlock, buffer);
    }

    public Hash hashPiece(int pieceIndex) {
        BlockManager mgr = getBlockManager(pieceIndex);
        assert(mgr != null);
        assert(mgr.getByteBuffersCount() == 0); // all buffers must be released
        blockMgrs.remove(mgr);
        return mgr.pieceHash();
    }

    /**
     * close file and release resources
     * @throws JED2KException
     */
    public void releaseFile() throws JED2KException {
        if (file != null) {
            try {
                try {
                    channel.close();
                } finally {
                    file.close();
                }
            } catch(IOException e) {
                throw new JED2KException(ErrorCode.IO_EXCEPTION);
            } finally {
                file = null;
                channel = null;
            }
        }
    }

    /**
     * delete file on disk
     */
    public void deleteFile() throws JED2KException {
        assert file == null;
        assert channel == null;
        File f = new File(filepath);
        f.delete();
    }

    final String getFilepath() {
        return filepath;
    }
}