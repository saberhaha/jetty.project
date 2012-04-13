// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * Channel End Point.
 * <p>Holds the channel and socket for an NIO endpoint.
 *
 */
public class ChannelEndPoint implements EndPoint
{
    private static final Logger LOG = Log.getLogger(ChannelEndPoint.class);

    private final ByteChannel _channel;
    private final Socket _socket;
    private final InetSocketAddress _local;
    private final InetSocketAddress _remote;
    private volatile int _maxIdleTime;
    private volatile boolean _ishut;
    private volatile boolean _oshut;

    public ChannelEndPoint(ByteChannel channel) throws IOException
    {
        super();
        this._channel = channel;
        _socket=(channel instanceof SocketChannel)?((SocketChannel)channel).socket():null;
        if (_socket!=null)
        {
            _local=(InetSocketAddress)_socket.getLocalSocketAddress();
            _remote=(InetSocketAddress)_socket.getRemoteSocketAddress();
            _maxIdleTime=_socket.getSoTimeout();
        }
        else
        {
            _local=_remote=null;
        }
    }

    protected ChannelEndPoint(ByteChannel channel, int maxIdleTime) throws IOException
    {
        this._channel = channel;
        _maxIdleTime=maxIdleTime;
        _socket=(channel instanceof SocketChannel)?((SocketChannel)channel).socket():null;
        if (_socket!=null)
        {
            _local=(InetSocketAddress)_socket.getLocalSocketAddress();
            _remote=(InetSocketAddress)_socket.getRemoteSocketAddress();
            _socket.setSoTimeout(_maxIdleTime);
        }
        else
        {
            _local=_remote=null;
        }
    }

    
    public boolean isBlocking()
    {
        return  !(_channel instanceof SelectableChannel) || ((SelectableChannel)_channel).isBlocking();
    }

    /*
     * @see org.eclipse.io.EndPoint#isOpen()
     */
    @Override
    public boolean isOpen()
    {
        return _channel.isOpen();
    }

    /** Shutdown the channel Input.
     * Cannot be overridden. To override, see {@link #shutdownInput()}
     * @throws IOException
     */
    protected final void shutdownChannelInput() throws IOException
    {
        LOG.debug("ishut {}", this);
        _ishut = true;
        if (_channel.isOpen())
        {
            if (_socket != null)
            {
                try
                {
                    if (!_socket.isInputShutdown())
                    {
                        _socket.shutdownInput();
                    }
                }
                catch (SocketException e)
                {
                    LOG.debug(e.toString());
                    LOG.ignore(e);
                }
                finally
                {
                    if (_oshut)
                    {
                        close();
                    }
                }
            }
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#close()
     */
    @Override
    public void shutdownInput() throws IOException
    {
        shutdownChannelInput();
    }

    protected final void shutdownChannelOutput() throws IOException
    {
        LOG.debug("oshut {}",this);
        _oshut = true;
        if (_channel.isOpen())
        {
            if (_socket != null)
            {
                try
                {
                    if (!_socket.isOutputShutdown())
                    {
                        _socket.shutdownOutput();
                    }
                }
                catch (SocketException e)
                {
                    LOG.debug(e.toString());
                    LOG.ignore(e);
                }
                finally
                {
                    if (_ishut)
                    {
                        close();
                    }
                }
            }
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#close()
     */
    @Override
    public void shutdownOutput() throws IOException
    {
        shutdownChannelOutput();
    }

    @Override
    public boolean isOutputShutdown()
    {
        return _oshut || !_channel.isOpen() || _socket != null && _socket.isOutputShutdown();
    }

    @Override
    public boolean isInputShutdown()
    {
        return _ishut || !_channel.isOpen() || _socket != null && _socket.isInputShutdown();
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#close()
     */
    @Override
    public void close() throws IOException
    {
        LOG.debug("close {}",this);
        _channel.close();
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#fill(org.eclipse.io.Buffer)
     */
    @Override
    public int fill(ByteBuffer buffer) throws IOException
    {
        if (_ishut)
            return -1;

        int pos=BufferUtil.flipToFill(buffer);
        try
        {
            int filled = _channel.read(buffer);

            if (filled==-1)
                shutdownInput();
            
            return filled;
        }
        catch(IOException e)
        {
            LOG.debug(e);
            shutdownInput();
            return -1;
        }
        finally
        {
            BufferUtil.flipToFlush(buffer,pos);
        }
    }

    /* (non-Javadoc)
     * @see org.eclipse.io.EndPoint#flush(org.eclipse.io.Buffer, org.eclipse.io.Buffer, org.eclipse.io.Buffer)
     */
    @Override
    public int flush(ByteBuffer... buffers) throws IOException
    {
        int len=0;
        if (buffers.length==1)
            len=_channel.write(buffers[0]);
        else if (buffers.length>1 && _channel instanceof GatheringByteChannel)
            len= (int)((GatheringByteChannel)_channel).write(buffers,0,buffers.length);
        else
        {
            for (ByteBuffer b : buffers)
            {
                if (b.hasRemaining())
                {
                    int l=_channel.write(b);
                    if (l>0)
                        len+=l;
                    else
                        break;
                }
            }
        }
        return len;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the channel.
     */
    public ByteChannel getChannel()
    {
        return _channel;
    }

    /* ------------------------------------------------------------ */
    @Override
    public InetSocketAddress getLocalAddress()
    {
        return _local;
    }

    /* ------------------------------------------------------------ */
    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return _remote;
    }

    /* ------------------------------------------------------------ */
    @Override
    public Object getTransport()
    {
        return _channel;
    }
    
    /* ------------------------------------------------------------ */
    public Socket getSocket()
    {
        return _socket;
    }

    /* ------------------------------------------------------------ */
    @Override
    public int getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.bio.StreamEndPoint#setMaxIdleTime(int)
     */
    @Override
    public void setMaxIdleTime(int timeMs) throws IOException
    {
        //if (_socket!=null && timeMs!=_maxIdleTime)
        //    _socket.setSoTimeout(timeMs>0?timeMs:0);
        _maxIdleTime=timeMs;
    }
    
}