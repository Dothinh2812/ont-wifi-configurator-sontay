package vn.vnpt.ontconfig.core.driver

open class OntDriverException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Raised when numberLogin >= 2 to prevent 180s hardware lockout. */
class OntAccountLockoutWarning(message: String) : OntDriverException(message)

/** Raised when timeoutLogin > 0 or numberLogin >= 3 (ONT is locked). */
class OntAccountLockoutError(message: String) : OntDriverException(message)

/** Raised when LuCI reports another session is currently active ("user exist"). */
class OntUserExistError(message: String) : OntDriverException(message)

/** Raised when all login attempts fail. */
class OntAuthenticationError(message: String) : OntDriverException(message)

/** Raised when input parameters fail strict validation. */
class OntValidationError(message: String) : OntDriverException(message)

/** Raised when physical or HTTP network request fails. */
open class OntNetworkError(message: String, cause: Throwable? = null) : OntDriverException(message, cause)

/** Raised on Connection 2 when live wlAssociatedDevice confirms phone is still associated with primary Band A. */
class StillOnPrimaryBandException(message: String) : OntNetworkError(message)
