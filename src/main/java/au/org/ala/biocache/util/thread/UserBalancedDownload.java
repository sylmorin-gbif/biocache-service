package au.org.ala.biocache.util.thread;

import au.org.ala.biocache.dto.DownloadDetailsDTO;

/**
 * This interface is for use on the Callable or Runnable that is submitted to the UserBalancedThreadPoolExecutor
 * for the purpose of identifying the user associated with a download.
 */
public interface UserBalancedDownload {
    DownloadDetailsDTO getDetails();
}
