package systems.diath.homeclaim.core.service

/** Result of a domain operation, kept separate from its authorization Decision. */
enum class OperationStatus {
    SUCCESS,
    DENIED,
    NOT_APPLICABLE,
    INVALID,
    NOT_FOUND,
    LIMIT_REACHED,
    CONFLICT,
    FAILED
}

data class OperationResult<out T>(
    val status: OperationStatus,
    val value: T? = null,
    val detail: String? = null
) {
    init {
        require(status == OperationStatus.SUCCESS || value == null) {
            "A rejected operation cannot contain a success value"
        }
    }

    val succeeded: Boolean get() = status == OperationStatus.SUCCESS

    companion object {
        fun <T> success(value: T): OperationResult<T> = OperationResult(OperationStatus.SUCCESS, value)
        fun <T> rejected(status: OperationStatus, detail: String): OperationResult<T> {
            require(status != OperationStatus.SUCCESS) { "Use success() for successful operations" }
            return OperationResult(status, detail = detail)
        }
    }
}
