package zlc.season.sange

import android.support.v7.widget.RecyclerView
import zlc.season.ironbranch.assertMainThreadWithResult
import zlc.season.ironbranch.ensureMainThread
import zlc.season.ironbranch.ioThread
import zlc.season.ironbranch.mainThread
import java.util.concurrent.atomic.AtomicBoolean

open class DataSource<T> {

    class Config(
        val useDiff: Boolean = true
    )

    protected open val dataStorage = DataStorage<T>()

    private val fetchingState = FetchingState()
    private val invalid = AtomicBoolean(false)
    private var pagingListDiffer = SangeListDiffer<T>()

    /**
     * Invalidate data source.
     */
    fun invalidate() {
        ensureMainThread {
            if (invalid.compareAndSet(false, true)) {
                dataStorage.clearAll()
                dispatchLoadInitial()
            }
        }
    }

    /**
     * Notify submit list.
     */
    fun notifySubmitList(initial: Boolean = false) {
        ensureMainThread {
            pagingListDiffer.submitList(dataStorage.all(), initial)
        }
    }

    fun clearAll(delay: Boolean = false) {
        ensureMainThread {
            dataStorage.clearAll()
            if (!delay) {
                notifySubmitList()
            }
        }
    }

    /**
     * Data functions
     */
    fun clear(delay: Boolean = false) {
        ensureMainThread {
            dataStorage.clear()
            if (!delay) {
                notifySubmitList()
            }
        }
    }

    fun add(t: T, position: Int = -1, delay: Boolean = false) {
        ensureMainThread {
            if (position > -1) {
                dataStorage.add(position, t)
            } else {
                dataStorage.add(t)
            }

            if (!delay) {
                notifySubmitList()
            }
        }
    }

    fun addAll(list: List<T>, position: Int = -1, delay: Boolean = false) {
        ensureMainThread {
            if (position > -1) {
                dataStorage.addAll(position, list)
            } else {
                dataStorage.addAll(list)
            }
            if (!delay) {
                notifySubmitList()
            }
        }
    }

    fun removeAt(position: Int, delay: Boolean = false) {
        ensureMainThread {
            dataStorage.removeAt(position)
            if (!delay) {
                notifySubmitList()
            }
        }
    }

    fun remove(t: T, delay: Boolean = false) {
        ensureMainThread {
            val index = dataStorage.indexOf(t)
            if (index != -1) {
                dataStorage.remove(t)
                if (!delay) {
                    notifySubmitList()
                }
            } else {
                throw IllegalArgumentException("Wrong index!")
            }
        }
    }

    fun set(old: T, new: T, delay: Boolean = false) {
        ensureMainThread {
            dataStorage.set(old, new)
            if (!delay) {
                notifySubmitList()
            }
        }
    }

    fun set(index: Int, new: T, delay: Boolean = false) {
        ensureMainThread {
            dataStorage.set(index, new)
            if (!delay) {
                notifySubmitList()
            }
        }
    }

    /**
     * return data for [position]
     */
    fun get(position: Int): T {
        return assertMainThreadWithResult {
            dataStorage.get(position)
        }
    }

    /**
     * return data size
     */
    fun size(): Int {
        return assertMainThreadWithResult {
            dataStorage.size()
        }
    }

    /**
     * Use [loadInitial] for initial loading, use [LoadCallback] callback
     * to set the result after loading is complete.
     */
    open fun loadInitial(loadCallback: LoadCallback<T>) {}

    /**
     * Use [loadAfter] for load next page, use [LoadCallback] callback
     * to set the result after loading is complete.
     */
    open fun loadAfter(loadCallback: LoadCallback<T>) {}

    internal fun setAdapter(adapter: RecyclerView.Adapter<*>?) {
        pagingListDiffer.adapter = adapter
        if (adapter != null) {
            invalidate()
        }
    }

    /**
     * Get total item count
     */
    fun getItemCount(): Int {
        return pagingListDiffer.size()
    }

    /**
     * Get item for [position]
     */
    fun getItem(position: Int): T {
        return pagingListDiffer.get(position)
    }

    internal fun innerItemCount(): Int {
        return getItemCount()
    }

    internal fun innerItem(position: Int): T {
        dispatchLoadAround(position)
        return getItem(position)
    }

    private fun dispatchLoadInitial() {
        ioThread {
            loadInitial(object : LoadCallback<T> {
                override fun setResult(data: List<T>?, delay: Boolean) {
                    mainThread {
                        if (!data.isNullOrEmpty()) {
                            dataStorage.addAll(data)
                            if (!delay) {
                                notifySubmitList(true)
                            }
                        }
                        invalid.compareAndSet(true, false)

                        fetchingState.setState(FetchingState.READY_TO_FETCH)
                    }
                }
            })
        }
    }

    private fun dispatchLoadAround(position: Int) {
        if (isInvalid()) return

        if (position == getItemCount() - 1) {
            scheduleLoadAfter()
            return
        }
    }

    private fun scheduleLoadAfter() {
        if (fetchingState.isNotReady()) {
            return
        }

        onStateChanged(FetchingState.FETCHING)

        ioThread {
            loadAfter(object : LoadCallback<T> {
                override fun setResult(data: List<T>?, delay: Boolean) {
                    if (isInvalid()) return
                    mainThread {
                        if (isInvalid()) return@mainThread

                        if (data != null) {
                            if (data.isEmpty()) {
                                onStateChanged(FetchingState.DONE_FETCHING)
                            } else {
                                onStateChanged(FetchingState.READY_TO_FETCH)

                                dataStorage.addAll(data)

                                if (!delay) {
                                    notifySubmitList()
                                }
                            }
                        } else {
                            onStateChanged(FetchingState.FETCHING_ERROR)
                        }
                    }
                }
            })
        }
    }

    protected open fun onStateChanged(newState: Int) {
        fetchingState.setState(newState)
    }

    private fun isInvalid(): Boolean {
        return invalid.get()
    }

    interface LoadCallback<T> {
        fun setResult(data: List<T>?, delay: Boolean = false)
    }
}