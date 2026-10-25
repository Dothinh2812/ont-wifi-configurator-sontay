import Foundation

public class OntDriverException: NSError, @unchecked Sendable {
    public init(_ message: String, code: Int = 1000, underlyingError: Error? = nil) {
        var info: [String: Any] = [NSLocalizedDescriptionKey: message]
        if let underlying = underlyingError {
            info[NSUnderlyingErrorKey] = underlying
        }
        super.init(domain: "vn.vnpt.ontconfig.driver", code: code, userInfo: info)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

/// Raised when numberLogin >= 2 to prevent 180s hardware lockout.
public final class OntAccountLockoutWarning: OntDriverException, @unchecked Sendable {
    public init(_ message: String) {
        super.init(message, code: 1001)
    }
}

/// Raised when timeoutLogin > 0 or numberLogin >= 3 (ONT is locked).
public final class OntAccountLockoutError: OntDriverException, @unchecked Sendable {
    public init(_ message: String) {
        super.init(message, code: 1002)
    }
}

/// Raised when LuCI reports another session is currently active ("user exist").
public final class OntUserExistError: OntDriverException, @unchecked Sendable {
    public init(_ message: String) {
        super.init(message, code: 1003)
    }
}

/// Raised when all login attempts fail.
public final class OntAuthenticationError: OntDriverException, @unchecked Sendable {
    public init(_ message: String) {
        super.init(message, code: 1004)
    }
}

/// Raised when input parameters fail strict validation.
public final class OntValidationError: OntDriverException, @unchecked Sendable {
    public init(_ message: String) {
        super.init(message, code: 1005)
    }
}

/// Raised when physical or HTTP network request fails.
public final class OntNetworkError: OntDriverException, @unchecked Sendable {
    public init(_ message: String, cause: Error? = nil) {
        super.init(message, code: 1006, underlyingError: cause)
    }
}
