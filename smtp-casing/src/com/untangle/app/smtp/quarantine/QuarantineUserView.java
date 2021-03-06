/**
 * $Id$
 */
package com.untangle.app.smtp.quarantine;

/**
 * Interface for the end-user interface to the Quarantine system.
 */
public interface QuarantineUserView extends QuarantineManipulation
{

    /**
     * Get the account name from an encrypted token. <br>
     * <br>
     * Note that this does <b>not</b> throw NoSuchInboxException.
     */
    public String getAccountFromToken(String token) throws BadTokenException;

    /**
     * Request a digest email to the given address
     * 
     * @param account
     *            the target account
     * 
     * @return true if the digest email could be sent (not nessecerially delivered yet). False if some rules (based on
     *         the address mean that this could never be delivered).
     */
    public boolean requestDigestEmail(String account) throws NoSuchInboxException, QuarantineUserActionFailedException;

    /**
     * Request that an email address (inbox) map to another. This is the gesture of someone "giving" their account to
     * someone else (hence, there aren't complex permission problems). <br>
     * Note that it is up to the <b>calling</b> application to ensure the user currently is logged-in as the source of
     * the remap
     * 
     * @param from
     *            the address to be redirected
     * @param to
     *            the target of the redirection
     * 
     * @exception InboxAlreadyRemappedException
     *                If this is a group alias and someone else already created the remap (we follow "first one wins"
     *                semantics).
     * 
     */
    public void remapSelfService(String from, String to) throws QuarantineUserActionFailedException,
            InboxAlreadyRemappedException;

    /**
     * Undoes {@link #remapSelfService remapSelfService}.
     * 
     * @param inboxName
     *            the name of the inbox which is currently <b>receiving</b> the remap.
     * 
     * @param aliasToRemove
     *            the alias to no longer be remapped (and presumably go back to its owner).
     * 
     * @return false if the mapping didn't exist
     */
    public boolean unmapSelfService(String inboxName, String aliasToRemove) throws QuarantineUserActionFailedException;

    /**
     * Test if this address is being remapped to another
     * 
     * @return the address to-which this is remapped, or null if this address is not remapped
     */
    public String getMappedTo(String account) throws QuarantineUserActionFailedException;

    /**
     * List any addresses for-which this account receives redirection.
     * 
     * @return all addresses redirected to this account, or a zero-length array if none are remapped.
     */
    public String[] getMappedFrom(String account) throws QuarantineUserActionFailedException;

}
