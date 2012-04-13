package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


public abstract class SelectableConnection implements Connection
{
    private static final Logger LOG = Log.getLogger(SelectableConnection.class);

    protected final Lock _lock=new ReentrantLock();
    protected final SelectableEndPoint _endp;
    private final long _createdTimeStamp;
    private final Condition _readable=_lock.newCondition();
    private final Condition _writeable=_lock.newCondition();
    private Thread _readBlocked;
    private boolean _writeBlocked;

    private final Runnable _reader=new Runnable()
    {
        @Override
        public void run() 
        {
            try
            {
                doRead();
            }
            catch(Throwable th)
            {
                LOG.warn(th);
            }
        }
    };
    private final Runnable _writer=new Runnable()
    {
        @Override
        public void run()
        {
            try
            {
                doWrite();
            }
            catch(Throwable th)
            {
                LOG.warn(th);
            }
        }
    };
    
    private volatile int _maxIdleTime=-1;

    public SelectableConnection(SelectableEndPoint endp)
    {
        _endp=endp;
        _createdTimeStamp = System.currentTimeMillis();
    }
    
    @Override
    public EndPoint getEndPoint()
    {
        return _endp;
    }
    
    public SelectableEndPoint getSelectableEndPoint()
    {
        return _endp;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return _createdTimeStamp;
    }
    
    public Runnable onReadable()
    {
        _lock.lock();
        try
        {
            if (_readBlocked!=null)
                _readable.signalAll();
            else 
                return _reader;
        }
        finally
        {
            _lock.unlock();
        }
        return null;
    }

    public Runnable onWriteable()
    {
        _lock.lock();
        try
        {
            if (_writeBlocked)
                _writeable.signalAll();
            else 
                return _writer;
        }
        finally
        {
            _lock.unlock();
        }
        return null;
    }

    public boolean blockReadable()
    {
        _lock.lock();
        boolean readable=false;
        try
        {
            if (_readBlocked!=null)
            {
                System.err.println("Already blocked by "+_readBlocked);
                for (StackTraceElement e :_readBlocked.getStackTrace())
                    System.err.println("    at "+e);
                
                throw new IllegalStateException();
            }
            _readBlocked=Thread.currentThread();
            _endp.setReadInterested(true);
            readable=_readable.await(getMaxIdleTime(),TimeUnit.SECONDS);
        }
        catch(InterruptedException e)
        {
            LOG.ignore(e);
        }
        finally
        {
            if (!readable)
                _endp.setReadInterested(false);
            _readBlocked=null;
            _lock.unlock();
        }
        return readable;
    }
    
    public boolean blockWriteable()
    {
        _lock.lock();
        boolean writeable=false;
        try
        {
            if (_writeBlocked)
                throw new IllegalStateException();
            _writeBlocked=true;
            _endp.setWriteInterested(true);
            writeable=_writeable.await(getMaxIdleTime(),TimeUnit.SECONDS);
        }
        catch(InterruptedException e)
        {
            LOG.ignore(e);
        }
        finally
        {
            if (!writeable)
                _endp.setWriteInterested(false);
            _writeBlocked=false;
            _lock.unlock();
        }
        return writeable;
    }
    
    public void onIdleExpired(long idleForMs)
    {
        try
        {
            LOG.debug("onIdleExpired {}ms {} {}",idleForMs,this,_endp);
            if (_endp.isInputShutdown() || _endp.isOutputShutdown())
                _endp.close();
            else
                _endp.shutdownOutput();
        }
        catch(IOException e)
        {
            LOG.ignore(e);

            try
            {
                _endp.close();
            }
            catch(IOException e2)
            {
                LOG.ignore(e2);
            }
        }
    }
    
    protected void doRead()
    {
        throw new IllegalStateException();
    }
    
    protected void doWrite()
    {
        throw new IllegalStateException();
    }

    @Override
    public int getMaxIdleTime()
    {
        int max=_maxIdleTime;
        return (max==-1)?_endp.getMaxIdleTime():max;
    }
    
    public void setMaxIdleTime(int max)
    {
        _maxIdleTime=max;
    }
    
    @Override
    public String toString()
    {
        return String.format("%s@%x rb=%s wb=%b", getClass().getSimpleName(), hashCode(),_readBlocked,_writeBlocked);
    }

    public void onInputShutdown() throws IOException
    {        
    }

    public void onClose()
    {
    }
}