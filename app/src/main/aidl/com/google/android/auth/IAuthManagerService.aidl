package com.google.android.auth;

import android.accounts.Account;
import android.os.Bundle;

// The ordinary caller-scoped OAuth operation from microG's IAuthManagerService.
// The descriptor and explicit method ID are part of the provider's wire format.
interface IAuthManagerService {
    Bundle getTokenWithAccount(in Account account, String scope, in Bundle extras) = 4;
}
