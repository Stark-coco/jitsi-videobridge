/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge;

import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.locks.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;

import org.json.simple.*;

/**
 * Implements an <tt>RtpChannel</tt> with <tt>MediaType.VIDEO</tt>.
 *
 * @author Lyubomir Marinov
 */
public class VideoChannel
    extends RtpChannel
{
    /**
     * The maximum number of video RTP stream to be sent from Jitsi Videobridge
     * to the endpoint associated with this video <tt>Channel</tt>.
     */
    private Integer lastN;

    /**
     * The <tt>Endpoint</tt>s in the multipoint conference in which this
     * <tt>Channel</tt> is participating ordered by
     * {@link #conferenceSpeechActivity} and used by this <tt>Channel</tt> for
     * the support of {@link #lastN}.
     */
    private List<WeakReference<Endpoint>> lastNEndpoints;

    /**
     * The <tt>Object</tt> which synchronizes the access to
     * {@link #lastNEndpoints}.
     */
    private final ReadWriteLock lastNSyncRoot = new ReentrantReadWriteLock();

    /**
     * Initializes a new <tt>VideoChannel</tt> instance which is to have a
     * specific ID. The initialization is to be considered requested by a
     * specific <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique within
     * the list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance is listed there as well.
     * @param channelBundleId the ID of the channel-bundle this
     * <tt>VideoChannel</tt> is to be a part of (or <tt>null</tt> if no it is
     * not to be a part of a channel-bundle).
     * @throws Exception if an error occurs while initializing the new instance
     */
    public VideoChannel(Content content, String id, String channelBundleId)
        throws Exception
    {
        super(content, id, channelBundleId);
    }

    /**
     * Initializes a new <tt>VideoChannel</tt> instance which is to have a
     * specific ID. The initialization is to be considered requested by a
     * specific <tt>Content</tt>.
     *
     * @param content the <tt>Content</tt> which is initializing the new
     * instance
     * @param id the ID of the new instance. It is expected to be unique within
     * the list of <tt>Channel</tt>s listed in <tt>content</tt> while the new
     * instance is listed there as well.
     * @throws Exception if an error occurs while initializing the new instance
     */
    public VideoChannel(Content content, String id)
            throws Exception
    {
        super(content, id, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void describe(ColibriConferenceIQ.ChannelCommon commonIq)
    {
        ColibriConferenceIQ.Channel iq = (ColibriConferenceIQ.Channel) commonIq;

        super.describe(iq);

        iq.setLastN(lastN);
    }

    /**
     * Gets the maximum number of video RTP streams to be sent from Jitsi
     * Videobridge to the endpoint associated with this video <tt>Channel</tt>.
     *
     * @return the maximum number of video RTP streams to be sent from Jitsi
     * Videobridge to the endpoint associated with this video <tt>Channel</tt>.
     * If no value or <tt>null</tt> has been explicitly set or this is not a
     * video <tt>Channel</tt>, returns <tt>-1</tt>.
     */
    private int getLastN()
    {
        Integer lastNInteger = this.lastN;

        return (lastNInteger == null) ? -1 : lastNInteger.intValue();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isInLastN(Channel channel)
    {
        int lastN = getLastN();

        if (lastN < 0)
            return true;

        Endpoint channelEndpoint = channel.getEndpoint();

        if (channelEndpoint == null)
            return true;

        ConferenceSpeechActivity conferenceSpeechActivity
            = this.conferenceSpeechActivity;

        if (conferenceSpeechActivity == null)
            return true;
        if (lastN == 0)
            return false;

        Lock writeLock = lastNSyncRoot.writeLock();
        Lock readLock = lastNSyncRoot.readLock();
        boolean inLastN = false;

        writeLock.lock();
        try
        {
            if (lastNEndpoints == null)
            {
                /*
                 * Pretend that the ordered list of Endpoints maintained by
                 * conferenceSpeechActivity has changed in order to populate
                 * lastNEndpoints.
                 */
                speechActivityEndpointsChanged(
                        conferenceSpeechActivity.getEndpoints());
            }
            readLock.lock();
        }
        finally
        {
            writeLock.unlock();
        }
        try
        {
            if (lastNEndpoints != null)
            {
                Endpoint thisEndpoint = getEndpoint();
                int n = 0;

                for (WeakReference<Endpoint> wr : lastNEndpoints)
                {
                    Endpoint e = wr.get();

                    if (e != null)
                    {
                        if (e.equals(thisEndpoint))
                        {
                            continue;
                        }
                        else if (e.equals(channelEndpoint))
                        {
                            inLastN = true;
                            break;
                        }
                    }

                    ++n;
                    if (n >= lastN)
                        break;
                }
            }
        }
        finally
        {
            readLock.unlock();
        }
        return inLastN;
    }

    /**
     * Notifies this instance that the list of <tt>Endpoint</tt>s defined by
     * {@link #lastN} has changed.
     *
     * @param endpointsEnteringLastN the <tt>Endpoint</tt>s which are entering
     * the list of <tt>Endpoint</tt>s defined by <tt>lastN</tt>
     */
    private void lastNEndpointsChanged(List<Endpoint> endpointsEnteringLastN)
    {
        int lastN = getLastN();

        if (lastN < 0)
            return;

        Endpoint endpoint = getEndpoint();

        if (endpoint == null)
            return;

        // Represent the list of Endpoints defined by lastN in JSON format.
        Lock readLock = lastNSyncRoot.readLock();
        StringBuilder lastNEndpointsStr = new StringBuilder();

        readLock.lock();
        try
        {
            if ((lastNEndpoints != null) && !lastNEndpoints.isEmpty())
            {
                int n = 0;

                for (WeakReference<Endpoint> wr : lastNEndpoints)
                {
                    Endpoint e = wr.get();

                    if (e != null)
                    {
                        if (e.equals(endpoint))
                        {
                            continue;
                        }
                        else
                        {
                            if (lastNEndpointsStr.length() != 0)
                                lastNEndpointsStr.append(',');
                            lastNEndpointsStr.append('"');
                            lastNEndpointsStr.append(
                                    JSONValue.escape(e.getID()));
                            lastNEndpointsStr.append('"');
                        }
                    }

                    ++n;
                    if (n >= lastN)
                        break;
                }
            }
        }
        finally
        {
            readLock.unlock();
        }

        // colibriClass
        StringBuilder msg
            = new StringBuilder(
                    "{\"colibriClass\":\"LastNEndpointsChangeEvent\"");

        // lastNEndpoints
        msg.append(",\"lastNEndpoints\":[");
        msg.append(lastNEndpointsStr);
        msg.append(']');

        // endpointsEnteringLastN
        if ((endpointsEnteringLastN != null)
                && !endpointsEnteringLastN.isEmpty())
        {
            StringBuilder endpointEnteringLastNStr = new StringBuilder();

            for (Endpoint e : endpointsEnteringLastN)
            {
                if (endpointEnteringLastNStr.length() != 0)
                    endpointEnteringLastNStr.append(',');
                endpointEnteringLastNStr.append('"');
                endpointEnteringLastNStr.append(
                        JSONValue.escape(e.getID()));
                endpointEnteringLastNStr.append('"');
            }
            if (endpointEnteringLastNStr.length() != 0)
            {
                msg.append(",\"endpointsEnteringLastN\":[");
                msg.append(endpointEnteringLastNStr);
                msg.append(']');
            }
        }

        msg.append('}');
        endpoint.sendMessageOnDataChannel(msg.toString());
    }

    /**
     * Gets the index of a specific <tt>Endpoint</tt> in a specific list of
     * <tt>lastN</tt> <tt>Endpoint</tt>s.
     *
     * @param endpoints the list of <tt>Endpoint</tt>s into which to look for
     * <tt>endpoint</tt>
     * @param lastN the number of <tt>Endpoint</tt>s in <tt>endpoint</tt>s to
     * look through
     * @param endpoint the <tt>Endpoint</tt> to find within <tt>lastN</tt>
     * elements of <tt>endpoints</tt>
     * @return the <tt>lastN</tt> index of <tt>endpoint</tt> in
     * <tt>endpoints</tt> or <tt>-1</tt> if <tt>endpoint</tt> is not within the
     * <tt>lastN</tt> elements of <tt>endpoints</tt>
     */
    private int lastNIndexOf(
            List<Endpoint> endpoints,
            int lastN,
            Endpoint endpoint)
    {
        Endpoint thisEndpoint = getEndpoint();
        int n = 0;

        for (Endpoint e : endpoints)
        {
            if (e.equals(thisEndpoint))
                continue;
            else if (e.equals(endpoint))
                return n;

            ++n;
            if (n >= lastN)
                break;
        }
        return -1;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    boolean rtpTranslatorWillWrite(
            boolean data,
            byte[] buffer, int offset, int length,
            Channel source)
    {
        boolean accept = true;

        if (data && (source != null))
        {
            accept = isInLastN(source);
        }
        return accept;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setLastN(Integer lastN)
    {
        this.lastN = lastN;

        touch(); // It seems this Channel is still active.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    List<Endpoint> speechActivityEndpointsChanged(List<Endpoint> endpoints)
    {
        Lock writeLock = lastNSyncRoot.writeLock();
        List<Endpoint> endpointsEnteringLastN = null;
        boolean lastNEndpointsChanged = false;

        writeLock.lock();
        try
        {
            // Determine which Endpoints are entering the list of lastN.
            int lastN = getLastN();

            if (lastN > 0)
            {
                Endpoint thisEndpoint = getEndpoint();

                endpointsEnteringLastN = new ArrayList<Endpoint>(lastN);
                // At most the first lastN are entering the list of lastN.
                for (Endpoint e : endpoints)
                {
                    if (!e.equals(thisEndpoint))
                    {
                        endpointsEnteringLastN.add(e);
                        if (endpointsEnteringLastN.size() >= lastN)
                            break;
                    }
                }
                if ((lastNEndpoints == null) || lastNEndpoints.isEmpty())
                {
                    if (!endpointsEnteringLastN.isEmpty())
                        lastNEndpointsChanged = true;
                }
                else
                {
                    /*
                     * Some of these first lastN are already in the list of
                     * lastN.
                     */
                    int n = 0;

                    for (WeakReference<Endpoint> wr : lastNEndpoints)
                    {
                        Endpoint e = wr.get();

                        if (e != null)
                        {
                            if (e.equals(thisEndpoint))
                            {
                                continue;
                            }
                            else
                            {
                                endpointsEnteringLastN.remove(e);
                                if (lastNIndexOf(endpoints, lastN, e) < 0)
                                    lastNEndpointsChanged = true;
                            }
                        }

                        ++n;
                        if (n >= lastN)
                            break;
                    }
                }
            }

            // Remember the Endpoints for the purposes of lastN.
            lastNEndpoints
                = new ArrayList<WeakReference<Endpoint>>(endpoints.size());
            for (Endpoint endpoint : endpoints)
                lastNEndpoints.add(new WeakReference<Endpoint>(endpoint));
        }
        finally
        {
            writeLock.unlock();
        }

        // Notify about changes in the list of lastN.
        if (lastNEndpointsChanged)
            lastNEndpointsChanged(endpointsEnteringLastN);

        // Request keyframes from the Enpoints entering the list of lastN.
        return endpointsEnteringLastN;
    }
}