package com.cappielloantonio.tempo.repository;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.cappielloantonio.tempo.subsonic.models.Error;
import com.cappielloantonio.tempo.subsonic.models.ResponseStatus;
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse;

import org.junit.Test;

public class SystemRepositoryTest {

    @Test
    public void nullResponseIsNotARejection() {
        // This is the offline / non-2xx case: an unreachable device gets a
        // synthesized 504 from the only-if-cached interceptor, and any non-2xx
        // response has Retrofit put the payload in errorBody() and leave body()
        // (and therefore the SubsonicResponse we're passed) null. Reading that as
        // a credential rejection would tell the user to sign in when the real
        // problem is the network. This is the regression guard the finding is about.
        assertFalse(SystemRepository.isRejection(null));
    }

    @Test
    public void wrongUsernameOrPasswordIsARejection() {
        assertTrue(SystemRepository.isRejection(failedResponse(40)));
    }

    @Test
    public void tokenAuthNotSupportedIsARejection() {
        assertTrue(SystemRepository.isRejection(failedResponse(41)));
    }

    @Test
    public void notAuthorizedIsARejection() {
        assertTrue(SystemRepository.isRejection(failedResponse(50)));
    }

    @Test
    public void serverMustUpgradeIsNotARejection() {
        // Code 30: the server is too old. Signing in again cannot fix it, so the
        // car must not be offered a Sign in button.
        assertFalse(SystemRepository.isRejection(failedResponse(30)));
    }

    @Test
    public void failedStatusWithNoErrorObjectIsNotARejection() {
        SubsonicResponse response = new SubsonicResponse();
        response.setStatus(ResponseStatus.FAILED);
        response.setError(null);

        assertFalse(SystemRepository.isRejection(response));
    }

    @Test
    public void okStatusIsNotARejection() {
        SubsonicResponse response = new SubsonicResponse();
        response.setStatus(ResponseStatus.OK);

        assertFalse(SystemRepository.isRejection(response));
    }

    private static SubsonicResponse failedResponse(int errorCode) {
        Error error = new Error();
        error.setCode(errorCode);

        SubsonicResponse response = new SubsonicResponse();
        response.setStatus(ResponseStatus.FAILED);
        response.setError(error);

        return response;
    }
}
