import Foundation

/// Non-reentrant async/non-blocking mutex used to pause background gateway probes
/// while the 7-step LuCI CGI setup sequence is active on the single-threaded ONT uHTTPd.
public final class AsyncMutex: @unchecked Sendable {

    private let stateLock = NSLock()
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    public init() {}

    public var isLocked: Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return locked
    }

    /// Non-blocking lock acquisition for background/UI probes (`performGatewayProbeOnce`).
    public func tryLock() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        if locked {
            return false
        }
        locked = true
        return true
    }

    /// Suspending lock acquisition for the 7-step setup driver (`executeMobileSetup`).
    public func lock() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            stateLock.lock()
            if !locked {
                locked = true
                stateLock.unlock()
                continuation.resume()
            } else {
                waiters.append(continuation)
                stateLock.unlock()
            }
        }
    }

    public func unlock() {
        stateLock.lock()
        if !waiters.isEmpty {
            let next = waiters.removeFirst()
            stateLock.unlock()
            next.resume()
        } else {
            locked = false
            stateLock.unlock()
        }
    }
}
