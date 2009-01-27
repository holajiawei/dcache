package org.dcache.pool.repository;

import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.MissingResourceException;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.log4j.Logger;

import diskCacheV111.repository.SpaceMonitor;
import diskCacheV111.repository.SpaceRequestable;
import diskCacheV111.repository.CacheRepository;
import diskCacheV111.repository.FairQueueAllocation;
import diskCacheV111.repository.CacheRepositoryEntry;
import diskCacheV111.util.PnfsId;
import diskCacheV111.util.CacheException;
import diskCacheV111.util.event.CacheNeedSpaceEvent;
import diskCacheV111.util.event.CacheRepositoryEvent;
import diskCacheV111.util.event.CacheRepositoryListener;

/**
 * Abstract implementation of the CacheRepository interface.
 *
 * This class implements the parts of the interface, which are
 * believed to be used by all implementations. This covers the event
 * listener registration, event propagation, space monitor delegation,
 * and accounting of precious and reserved space.
 *
 * It does not implement repository entry handling.
 */
public abstract class AbstractCacheRepository
    implements CacheRepository, EventProcessor
{
    private static Logger _log =
        Logger.getLogger("logger.org.dcache.repository");
    private final static Logger _logSpaceAllocation = Logger.getLogger("logger.dev.org.dcache.poolspacemonitor." + AbstractCacheRepository.class.getName());

    /**
     * Registered event listeners.
     */
    private final List <CacheRepositoryListener> _repositoryListners =
        new CopyOnWriteArrayList<CacheRepositoryListener>();

    /**
     * Space monitor for bookkeeping.
     */
    private final SpaceMonitor _spaceMonitor;

    /**
     * Amount of precious space in the repository. This is the sum of
     * the size of all entries in <code>_precious</code>.
     */
    private long _preciousSpace = 0;

    /**
     * Set of precious entries.
     */
    private final Set<PnfsId> _precious = new HashSet<PnfsId>();

    /**
     * Utility class to bridge between space monitor and repository
     * event system.
     */
    private class NeedSpace implements SpaceRequestable
    {
        public void spaceNeeded(long space) {
            processEvent(EventType.SPACE, new CacheNeedSpaceEvent(this, space));
        }
    }

    public AbstractCacheRepository()
    {
        _spaceMonitor  = new FairQueueAllocation(0);
        _spaceMonitor.addSpaceRequestListener(new NeedSpace());
    }

    /**
     * Add repository listener.
     */
    public void addCacheRepositoryListener(CacheRepositoryListener listener)
    {
        if (_log.isDebugEnabled()) {
            _log.debug("adding listener: " + listener);
        }
        _repositoryListners.add(listener);
    }

    /**
     * Remove repository listener.
     */
    public void removeCacheRepositoryListener(CacheRepositoryListener listener)
    {
        if (_log.isDebugEnabled()) {
            _log.debug("removing listener: " + listener);
        }

        _repositoryListners.remove(listener);
    }

    public void addSpaceRequestListener(SpaceRequestable listener)
    {
        throw new IllegalArgumentException("Not supported");
    }

    /**
     * Adds an entry to the list of precious files. If the entry is
     * already in the list, then nothin happens.
     */
    private void addPrecious(CacheRepositoryEntry entry)
    {
        long size = entry.getSize();
        synchronized (_precious) {
            if (_precious.add(entry.getPnfsId())) {
                _preciousSpace += size;
            }
        }
    }

    /**
     * Removes an entry from the list of precious files. If the entry
     * is not in the list, then nothing happens.
     */
    private void removePrecious(CacheRepositoryEntry entry)
    {
        long size = entry.getSize();
        synchronized (_precious) {
            if (_precious.remove(entry.getPnfsId())) {
                _preciousSpace -= size;
            }
        }
    }

    /**
     * Triggers listener notification.
     */
    public void processEvent(EventType type, CacheRepositoryEvent event)
    {
        CacheRepositoryEntry entry;

        if (_log.isDebugEnabled()) {
            _log.debug("Broadcasting event: " + event + " type " + type);
        }

        switch (type) {
        case CACHED:
            removePrecious(event.getRepositoryEntry());
            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.cached(event);
            }
            break;

        case PRECIOUS:
            addPrecious(event.getRepositoryEntry());
            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.precious(event);
            }
            break;

        case CREATE:
            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.created(event);
            }
            break;

        case REMOVE:
            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.removed(event);
            }
            break;

        case TOUCH:
            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.touched(event);
            }
            break;

        case DESTROY:
            entry = event.getRepositoryEntry();
            _logSpaceAllocation.debug("FREE: " + entry.getPnfsId() 
                                      + " : " + entry.getSize() );
            freeSpace(entry.getSize());
            removePrecious(entry);
            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.destroyed(event);
            }
            break;

        case SCAN:
            try {
                entry = event.getRepositoryEntry();
                allocateSpace(entry.getSize(), SpaceMonitor.NONBLOCKING);
                if (entry.isPrecious()) {
                    addPrecious(entry);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("Bug detected", e);
            } catch (MissingResourceException e) {
                throw new RuntimeException("File registration failed: Pool is out of space.", e);
            } catch (CacheException ignored) {
            }

            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.scanned(event);
            }
            break;

        case AVAILABLE:
            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.available(event);
            }
            break;

        case STICKY:
            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.sticky(event);
            }
            break;

        case SPACE:
            if (!(event instanceof CacheNeedSpaceEvent))
                throw new IllegalArgumentException("SPACE events must be CacheNeedSpaceEvent");
            for (CacheRepositoryListener listener : _repositoryListners) {
                listener.needSpace((CacheNeedSpaceEvent)event);
            }
            break;
	}
    }

    /**
     * Move <i>space</i> bytes from free space into used space.
     * Remove some entries if space is needed. Blocks as long as space
     * is not available.
     */
    public void allocateSpace(long space) throws InterruptedException
    {
    	_logSpaceAllocation.debug("ALLOC: <UNKNOWN> : " + space );
        _spaceMonitor.allocateSpace(space);
    }

    /**
     * Move <i>space</i> bytes from free space into used space.
     * Remove some entries if space is needed. Blocks as long as space
     * is not available, but at most <i>millis</i>.
     */
    public void allocateSpace(long space, long millis)
        throws InterruptedException, MissingResourceException
    {
    	_logSpaceAllocation.debug("ALLOC: <UNKNOWN> : " + space );
        if (millis == SpaceMonitor.NONBLOCKING) {
            synchronized (_spaceMonitor) {
                if (_spaceMonitor.getFreeSpace() < space)
                    throw new
	                MissingResourceException("Not enough space left",
                                                 this.getClass().getName(),
                                                 "Space");
                _spaceMonitor.allocateSpace(space);
            }
        } else if (millis == SpaceMonitor.BLOCKING) {
            _spaceMonitor.allocateSpace(space);
        } else {
            _spaceMonitor.allocateSpace(space, millis);
        }
    }

    /**
     * Move <i>space</i> bytes from used into free space.
     */
    public void freeSpace(long space)
    {
        _spaceMonitor.freeSpace(space);
    }

    public long getFreeSpace()
    {
        return _spaceMonitor.getFreeSpace();
    }

    public long getTotalSpace()
    {
        return _spaceMonitor.getTotalSpace();
    }

    public void setTotalSpace(long space)
    {
        _spaceMonitor.setTotalSpace(space);
    }

    public long getPreciousSpace()
    {
        synchronized (_precious) {
            return _preciousSpace;
        }
    }
}
