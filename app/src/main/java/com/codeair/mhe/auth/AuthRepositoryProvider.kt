package com.codeair.mhe.auth

import android.content.Context
import com.codeair.mhe.auth.repository.AuthRepository
import com.codeair.mhe.auth.repository.FirebaseAuthRepository

object AuthRepositoryProvider {
    @Volatile
    private var instance: AuthRepository? = null

    fun get(context: Context): AuthRepository {
        GoogleSignInProvider.handler = OnlineGoogleSignInHandler()
        return instance ?: synchronized(this) {
            instance ?: FirebaseAuthRepository(context.applicationContext).also { instance = it }
        }
    }
}
