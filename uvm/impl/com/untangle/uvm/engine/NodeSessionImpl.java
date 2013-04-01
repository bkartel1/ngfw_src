/**
 * $Id$
 */
package com.untangle.uvm.engine;

import java.util.Map;

import com.untangle.uvm.vnet.ArgonConnector;
import com.untangle.uvm.vnet.NodeSession;
import com.untangle.uvm.node.SessionEvent;
import com.untangle.uvm.argon.ArgonNewSessionRequest;
import com.untangle.uvm.argon.ArgonAgent;
import com.untangle.uvm.argon.SessionGlobalState;
import com.untangle.uvm.argon.PipelineListener;
import com.untangle.jnetcap.NetcapSession;
import com.untangle.jvector.IncomingSocketQueue;
import com.untangle.jvector.OutgoingSocketQueue;
import com.untangle.jvector.ShutdownCrumb;
import com.untangle.jvector.SocketQueueListener;
import com.untangle.jvector.Vector;

import org.apache.log4j.Logger;

/**
 * Abstract base class for all live sessions
 */
public abstract class NodeSessionImpl implements NodeSession
{
    private final Logger logger = Logger.getLogger(NodeSessionImpl.class);
    
    protected int maxInputSize  = 0;
    protected int maxOutputSize = 0;

    protected final IncomingSocketQueue clientIncomingSocketQueue;
    protected final OutgoingSocketQueue clientOutgoingSocketQueue;

    protected final IncomingSocketQueue serverIncomingSocketQueue;
    protected final OutgoingSocketQueue serverOutgoingSocketQueue;

    protected final ArgonAgent argonAgent;

    protected final SessionGlobalState sessionGlobalState;

    protected boolean isServerShutdown = false;
    protected boolean isClientShutdown = false;

    protected final boolean isVectored;

    /* Using the null pipeline listener just in case so null doesn't
     * have to be checked everywhere */
    protected PipelineListener listener = NULL_PIPELINE_LISTENER;

    protected ArgonConnectorImpl argonConnector;

    protected final SessionEvent sessionEvent;

    protected volatile Object attachment = null;

    /**
     * NULL Pipeline listener used once an argon agent dies 
     * This allows the agent to be stopped without waiting for the entire pipeline to stop.
     */
    private static final PipelineListener NULL_PIPELINE_LISTENER = new PipelineListener() {
            public void clientEvent( IncomingSocketQueue in ) {}
            public void clientEvent( OutgoingSocketQueue out ) {}
            public void serverEvent( IncomingSocketQueue in ) {}
            public void serverOutputResetEvent( OutgoingSocketQueue out ) {}
            public void serverEvent( OutgoingSocketQueue out ) {}
            public void clientOutputResetEvent( OutgoingSocketQueue out ) {}
            public void raze() {}
            public void complete() {}
        };
    
    protected NodeSessionImpl( ArgonConnectorImpl argonConnector, SessionEvent sessionEvent, ArgonNewSessionRequest request, boolean isVectored )
    {
        this.argonConnector = argonConnector;
        this.sessionEvent = sessionEvent;

        sessionGlobalState        = request.sessionGlobalState();
        argonAgent                = request.argonAgent();

        if ( isVectored ) {
            this.isVectored           = true;

            clientIncomingSocketQueue = new IncomingSocketQueue();
            clientOutgoingSocketQueue = new OutgoingSocketQueue();

            serverIncomingSocketQueue = new IncomingSocketQueue();
            serverOutgoingSocketQueue = new OutgoingSocketQueue();
        } else {
            this.isVectored           = false;
            clientIncomingSocketQueue = null;
            clientOutgoingSocketQueue = null;

            serverIncomingSocketQueue = null;
            serverOutgoingSocketQueue = null;
        }
    }

    public ArgonConnector argonConnector()
    {
        return argonConnector;
    }

    public Object attach(Object ob)
    {
        Object oldOb = attachment;
        attachment = ob;
        return oldOb;
    }

    public Object attachment()
    {
        return attachment;
    }

    public Object globalAttach(String key, Object ob)
    {
        return this.sessionGlobalState().attach(key,ob);
    }

    public Object globalAttachment(String key)
    {
        return this.sessionGlobalState().attachment(key);
    }

    public Map<String,Object> getAttachments()
    {
        return this.sessionGlobalState().getAttachments();
    }
    
    public SessionGlobalState sessionGlobalState()
    {
        return sessionGlobalState;
    }

    public long id()
    {
        return sessionGlobalState.id();
    }

    public long getSessionId()
    {
        return sessionGlobalState.id();
    }
    
    public ArgonAgent argonAgent()
    {
        return argonAgent;
    }

    public NetcapSession netcapSession()
    {
        return sessionGlobalState.netcapSession();
    }

    public boolean isVectored()
    {
        return isVectored;
    }

    public String user()
    {
        return sessionGlobalState.user();
    }

    /**
     * Number of bytes received from the client.
     */
    public long c2tBytes()
    {
        return sessionGlobalState.clientSideListener().getRxBytes();
    }

    /**
     * Number of bytes transmitted to the server.
     */
    public long t2sBytes()
    {
        return sessionGlobalState.serverSideListener().getTxBytes();
    }

    /**
     * Number of bytes received from the server.
     */
    public long s2tBytes()
    {
        return sessionGlobalState.serverSideListener().getRxBytes();
    }

    /**
     * Number of bytes transmitted to the client.
     */
    public long t2cBytes()
    {
        return sessionGlobalState.clientSideListener().getTxBytes();
    }

    /**
     * Number of chunks received from the client.
     */
    public long c2tChunks()
    {
        return sessionGlobalState.clientSideListener().getRxChunks();
    }

    /**
     * Number of chunks transmitted to the server.
     */
    public long t2sChunks()
    {
        return sessionGlobalState.serverSideListener().getTxChunks();
    }

    /**
     * Number of chunks received from the server.
     */
    public long s2tChunks()
    {
        return sessionGlobalState.serverSideListener().getRxChunks();
    }

    /**
     * Number of chunks transmitted to the client.
     */
    public long t2cChunks()
    {
        return sessionGlobalState.clientSideListener().getTxChunks();
    }

    /* These are not really useable without a little bit of tweaking to the vector machine */
    public int maxInputSize()
    {
        return maxInputSize;
    }

    public void maxInputSize( int size )
    {
        /* Possibly throw an error on an invalid size */
        maxInputSize = size;
    }

    public int maxOutputSize()
    {
        return maxOutputSize;
    }

    public void maxOutputSize( int size )
    {
        /* Possibly throw an error on an invalid size */
        maxOutputSize = size;
    }

    /**
     * Shutdown the client side of the connection.
     */
    public void shutdownClient()
    {
        if ( !clientOutgoingSocketQueue.isClosed()) {
            clientOutgoingSocketQueue.write( ShutdownCrumb.getInstance());
        }
    }

    /**
     * Shutdown the server side of the connection.
     */
    public void shutdownServer()
    {
        if ( !serverOutgoingSocketQueue.isClosed()) {
            serverOutgoingSocketQueue.write( ShutdownCrumb.getInstance());
        }
    }

    /**
     * Kill the server and client side of the session, this should only be
     * called from the session thread.
     */
    public void killSession()
    {
        try {
            if ( clientIncomingSocketQueue != null ) clientIncomingSocketQueue.kill();
            if ( clientOutgoingSocketQueue != null ) clientOutgoingSocketQueue.kill();
            if ( serverIncomingSocketQueue != null ) serverIncomingSocketQueue.kill();
            if ( serverOutgoingSocketQueue != null ) serverOutgoingSocketQueue.kill();

            /* Call the raze method */
            if ( this.listener != null ) this.listener.raze();
        } catch ( Exception ex ) {
            logger.warn( "Error while killing a session", ex );
        }

        /* Replace the listener with a NULL Listener */
        this.listener = NULL_PIPELINE_LISTENER;

        try {
            Vector vector = sessionGlobalState.argonHook().getVector();

            /* Last send the kill signal to the vectoring machine */
            if ( vector != null ) vector.shutdown();
            else logger.info( "kill session called before vectoring started, ignoring vectoring." );
        } catch ( Exception ex ) {
            logger.warn( "Error while killing a session", ex );
        }
    }

    public void complete()
    {
        try {
            this.listener.complete();
        } catch ( Exception ex ) {
            logger.warn( "Error while completing a session", ex );
        }
    }

    /**
     * Register a listener a listener for the session.
     */
    public void registerListener( PipelineListener listener )
    {
        this.listener = listener;

        if ( isVectored ) {
            SocketQueueListener sqListener = new SessionSocketQueueListener();

            clientIncomingSocketQueue.registerListener( sqListener );
            clientOutgoingSocketQueue.registerListener( sqListener );
            serverIncomingSocketQueue.registerListener( sqListener );
            serverOutgoingSocketQueue.registerListener( sqListener );
        }
    }

    /**
     * Package method to just call the raze method.
     */
    public void raze()
    {
        if ( this.listener != null ) this.listener.raze();

        /* Raze the incoming and outgoing socket queues */
        if ( clientIncomingSocketQueue != null ) clientIncomingSocketQueue.raze();
        if ( clientOutgoingSocketQueue != null ) clientOutgoingSocketQueue.raze();
        if ( serverIncomingSocketQueue != null ) serverIncomingSocketQueue.raze();
        if ( serverOutgoingSocketQueue != null ) serverOutgoingSocketQueue.raze();
    }

    /**
     * XXX All this does is remove the session from the argon agent table, this is being
     * done from the tapi, so it is no longer necessary
     */
    public void shutdownEvent( OutgoingSocketQueue osq )
    {
        logger.debug( "Outgoing socket queue shutdown event: " + osq );

        if ( osq == clientOutgoingSocketQueue ) {
            isClientShutdown = true;
        } else if ( osq == serverOutgoingSocketQueue ) {
            isServerShutdown = true;
        } else {
            logger.error( "Unknown shutdown socket queue: " + osq );
            return;
        }

        /* Remove the session from the argon agent table */
        if ( isClientShutdown && isServerShutdown ) {
            argonAgent.removeSession( this );
        }
    }

    public boolean getServerShutdown() { return this.isServerShutdown; }
    public void setServerShutdown( boolean newValue ) { this.isServerShutdown = newValue; }

    public boolean getClientShutdown() { return this.isClientShutdown; }
    public void setClientShutdown( boolean newValue ) { this.isClientShutdown = newValue; }
    
    public void shutdownEvent( IncomingSocketQueue isq )
    {
        logger.debug( "Incoming socket queue shutdown event: " + isq );
    }

    public IncomingSocketQueue clientIncomingSocketQueue()
    {
        if ( clientIncomingSocketQueue == null || clientIncomingSocketQueue.isClosed()) return null;
        return clientIncomingSocketQueue;
    }

    public OutgoingSocketQueue clientOutgoingSocketQueue()
    {
        if ( clientOutgoingSocketQueue == null || clientOutgoingSocketQueue.isClosed()) return null;
        return clientOutgoingSocketQueue;
    }

    public IncomingSocketQueue serverIncomingSocketQueue()
    {
        if ( serverIncomingSocketQueue == null || serverIncomingSocketQueue.isClosed()) return null;
        return serverIncomingSocketQueue;
    }

    public OutgoingSocketQueue serverOutgoingSocketQueue()
    {
        if ( serverOutgoingSocketQueue == null || serverOutgoingSocketQueue.isClosed()) return null;
        return serverOutgoingSocketQueue;
    }

    private class SessionSocketQueueListener implements SocketQueueListener
    {
        SessionSocketQueueListener() { }

        public void event( IncomingSocketQueue in )
        {
            if ( in == serverIncomingSocketQueue ) {
                if ( logger.isDebugEnabled()) {
                    logger.debug( "IncomingSocketQueueEvent: server - " + in + " " + sessionGlobalState );
                }

                listener.serverEvent( in );
            } else if ( in == clientIncomingSocketQueue ) {
                if ( logger.isDebugEnabled()) {
                    logger.debug( "IncomingSocketQueueEvent: client - " + in + " " + sessionGlobalState );
                }

                listener.clientEvent( in );
            } else {
                /* This should never happen */
                throw new IllegalStateException( "Invalid socket queue: " + in );
            }
        }

        public void event( OutgoingSocketQueue out )
        {
            /**
             * This is called every time a crumb is removed from the
             * outgoing socket queue (what it considers 'writable',
             * but the TAPI defines writable as empty) So, we drop all
             * these writable events unless it is empty. That converts
             * the socketqueue's definition of writable to the TAPI's
             * You are at no risk of spinning because this is only
             * called when something is actually removed from the
             * SocketQueue
             **/
            if (!out.isEmpty())
                return;

            if ( out == serverOutgoingSocketQueue ) {
                if ( logger.isDebugEnabled()) {
                    logger.debug( "OutgoingSocketQueueEvent: server - " + out + " " + sessionGlobalState);
                }

                listener.serverEvent( out );
            } else if ( out == clientOutgoingSocketQueue ) {
                if ( logger.isDebugEnabled()) {
                    logger.debug( "OutgoingSocketQueueEvent: client - " + out + " " + sessionGlobalState);
                }

                listener.clientEvent( out );
            } else {
                /* This should never happen */
                throw new IllegalStateException( "Invalid socket queue: " + out );
            }
        }

        public void shutdownEvent( IncomingSocketQueue in )
        {
            if ( in == serverIncomingSocketQueue ) {
                if ( logger.isDebugEnabled())
                    logger.debug( "ShutdownEvent: server - " + in );
            } else if ( in == clientIncomingSocketQueue ) {
                if ( logger.isDebugEnabled())
                    logger.debug( "ShutdownEvent: client - " + in );
            } else {
                /* This should never happen */
                throw new IllegalStateException( "Invalid socket queue: " + in );
            }
        }

        /** This occurs when the outgoing socket queue is shutdown */
        public void shutdownEvent( OutgoingSocketQueue out )
        {
            boolean isDebugEnabled = logger.isDebugEnabled();
            if ( out == serverOutgoingSocketQueue ) {
                if ( isDebugEnabled ) {
                    logger.debug( "ShutdownEvent: server - " + out + " closed: " + out.isClosed());
                }
                /* If the node hasn't closed the socket queue yet, send the even */
                if ( !out.isClosed()) {
                    /* This is equivalent to an EPIPE */
                    listener.serverOutputResetEvent( out );
                } else {
                    if ( isDebugEnabled ) logger.debug( "shutdown event for closed sink" );
                }
            } else if ( out == clientOutgoingSocketQueue ) {
                if ( isDebugEnabled ) {
                    logger.debug( "ShutdownEvent: client - " + out + " closed: " + out.isClosed());
                }

                if ( !out.isClosed()) {
                    /* This is equivalent to an EPIPE */
                    listener.clientOutputResetEvent( out );
                } else {
                    if ( isDebugEnabled ) logger.debug( "shutdown event for closed sink" );
                }
            } else {
                /* This should never happen */
                throw new IllegalStateException( "Invalid socket queue: " + out );
            }
        }
    }
}
