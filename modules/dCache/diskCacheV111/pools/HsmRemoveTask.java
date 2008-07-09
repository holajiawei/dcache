package diskCacheV111.pools;

import dmg.cells.nucleus.CellAdapter;
import dmg.cells.nucleus.CellMessage;
import dmg.util.Logable;
import diskCacheV111.util.HsmSet;
import diskCacheV111.util.ExternalTask;
import diskCacheV111.vehicles.PoolRemoveFilesFromHSMMessage;

import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.net.URI;
import org.apache.log4j.Logger;

/**
 * Encapsulates the task to process a PoolRemoveFilesFromHSMMessage.
 */
public class HsmRemoveTask implements Runnable
{
    /** Logging target.
     */
    private final static Logger _log = Logger.getLogger(HsmRemoveTask.class);

    /** The cell used to send back a reply to the requester.
     */
    private final CellAdapter _cell;

    /**
     * HSM configuration component.
     */
    private final HsmSet      _hsmSet;

    /**
     * The message being processed.
     */
    private final CellMessage _message;

    /**
     * Executor used to process each individual delete.
     */
    private final Executor    _executor;

    /**
     * Timeout for an individual remove operation.
     */
    private final long        _timeout;

    @Deprecated
    public HsmRemoveTask(CellAdapter cell, Logable log,
                         Executor executor, HsmSet hsmSet,
                         long timeout, CellMessage message)
    {
        this(cell, executor, hsmSet, timeout, message);
    }

    public HsmRemoveTask(CellAdapter cell,
                         Executor executor, HsmSet hsmSet,
                         long timeout, CellMessage message)
    {
        assert message.getMessageObject() instanceof PoolRemoveFilesFromHSMMessage;
        _cell = cell;
        _executor = executor;
        _hsmSet = hsmSet;
        _timeout = timeout;
        _message = message;
    }

    private String getCommand(URI uri)
    {
        String instance = uri.getAuthority();
        HsmSet.HsmInfo hsm = _hsmSet.getHsmInfoByName(instance);
        if (hsm == null) {
            throw new
                IllegalArgumentException("HSM instance " + instance + " not defined");
        }

        String command = hsm.getAttribute("command");
        if (command == null) {
            throw new
                IllegalArgumentException("command not specified for HSM instance " + instance);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(command).append(" remove");
        sb.append(" -uri=").append(uri);
        for (Map.Entry<String,String> attr : hsm.attributes()) {
            String key = attr.getKey();
            String val = attr.getValue();
            sb.append(" -").append(key);
            if (val != null && val.length() > 0)
                sb.append("=").append(val) ;
        }

        return sb.toString();
    }

    public void run()
    {
        try {
            PoolRemoveFilesFromHSMMessage msg =
                (PoolRemoveFilesFromHSMMessage)_message.getMessageObject();

            Collection<URI> files = msg.getFiles();
            Collection<FutureTask<Integer>> tasks =
                new ArrayList<FutureTask<Integer>>(files.size());

            /* Submit tasks.
             */
            for (URI uri : files) {
                String command = getCommand(uri);
                ExternalTask task = new ExternalTask(_timeout, command);
                FutureTask<Integer> future = new FutureTask<Integer>(task);
                tasks.add(future);
                _executor.execute(future);
            }

            /* Wait for completion.
             */
            Collection<URI> succeeded = new ArrayList<URI>(files.size());
            Collection<URI> failed = new ArrayList<URI>();
            Iterator<FutureTask<Integer>> i = tasks.iterator();
            for (URI uri : files) {
                int rc = i.next().get();
                if (rc == 0) {
                    succeeded.add(uri);
                } else {
                    _log.error(String.format("Failed to delete %s: HSM script terminated with non-zero return code %d.", uri, rc));
                    failed.add(uri);
                }
            }

            /* Generate reply.
             */
            try {
                msg.setResult(succeeded, failed);
                _message.revertDirection();
                _cell.sendMessage(_message);
            } catch (Exception e) {
                _log.error("Cannot send reply: " + e);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (CancellationException e) {
            /* Somebody cancelled the future, even though we are the
             * only one holding a reference to it. Must be a bug.
             */
            _log.error(e.toString());
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            /* This means the ExternalTask threw an exception. This
             * smells like a bug, so we better log it.
             */
            _log.error(e.toString());
            throw new RuntimeException(e);
        }
    }
}