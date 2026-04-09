/**
 * HomeClaim WebUX - Global App Logic
 * 
 * Alpine.js Components & WebSocket Integration
 */

// WebSocket Connection Manager
class WebSocketManager {
    constructor(url) {
        this.url = url;
        this.ws = null;
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.listeners = new Map();
        this.connect();
    }

    connect() {
        if (this.ws?.readyState === WebSocket.OPEN) return;

        this.ws = new WebSocket(this.url);

        this.ws.onopen = () => {
            console.log('[WS] Connected to HomeClaim server');
            this.reconnectAttempts = 0;
            this.dispatch('connected', {});
        };

        this.ws.onmessage = (event) => {
            try {
                const message = JSON.parse(event.data);
                this.dispatch(message.type, message.data);
            } catch (error) {
                console.error('[WS] Failed to parse message:', error);
            }
        };

        this.ws.onerror = (error) => {
            console.error('[WS] Error:', error);
            this.dispatch('error', error);
        };

        this.ws.onclose = () => {
            console.log('[WS] Disconnected');
            this.dispatch('disconnected', {});
            this.attemptReconnect();
        };
    }

    attemptReconnect() {
        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            console.error('[WS] Max reconnect attempts reached');
            return;
        }

        const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempts), 10000);
        this.reconnectAttempts++;

        console.log(`[WS] Reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);
        setTimeout(() => this.connect(), delay);
    }

    send(type, data) {
        if (this.ws?.readyState === WebSocket.OPEN) {
            this.ws.send(JSON.stringify({ type, data }));
        } else {
            console.warn('[WS] Cannot send message, not connected');
        }
    }

    on(event, callback) {
        if (!this.listeners.has(event)) {
            this.listeners.set(event, []);
        }
        this.listeners.get(event).push(callback);
    }

    dispatch(event, data) {
        const callbacks = this.listeners.get(event) || [];
        callbacks.forEach(cb => cb(data));
    }

    disconnect() {
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
    }
}

// Initialize WebSocket (after Alpine)
let wsManager = null;

document.addEventListener('alpine:init', () => {
    // Global WebSocket State
    Alpine.store('ws', {
        connected: false,
        manager: null,

        init() {
            const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
            const wsUrl = `${protocol}//${window.location.host}/ws`;
            
            this.manager = new WebSocketManager(wsUrl);
            wsManager = this.manager;

            this.manager.on('connected', () => {
                this.connected = true;
            });

            this.manager.on('disconnected', () => {
                this.connected = false;
            });
        },

        send(type, data) {
            this.manager?.send(type, data);
        }
    });

    // Plot List Component
    Alpine.data('plotList', () => ({
        plots: [],
        loading: true,
        filter: 'all', // 'all', 'featured', 'owned'
        sortBy: 'recent', // 'recent', 'popular', 'likes'

        async init() {
            await this.loadPlots();
            this.listenToUpdates();
        },

        async loadPlots() {
            try {
                const response = await fetch('/api/plots');
                this.plots = await response.json();
            } catch (error) {
                console.error('Failed to load plots:', error);
            } finally {
                this.loading = false;
            }
        },

        listenToUpdates() {
            wsManager?.on('plot_updated', (plot) => {
                const index = this.plots.findIndex(p => p.id === plot.id);
                if (index >= 0) {
                    this.plots[index] = plot;
                }
            });

            wsManager?.on('plot_created', (plot) => {
                this.plots.unshift(plot);
            });
        },

        get filteredPlots() {
            let filtered = this.plots;

            if (this.filter === 'featured') {
                filtered = filtered.filter(p => p.featured);
            } else if (this.filter === 'owned') {
                filtered = filtered.filter(p => p.isOwner);
            }

            // Sort
            if (this.sortBy === 'popular') {
                return filtered.sort((a, b) => b.visits - a.visits);
            } else if (this.sortBy === 'likes') {
                return filtered.sort((a, b) => b.likes - a.likes);
            }

            return filtered;
        }
    }));

    // Plot Detail Component
    Alpine.data('plotDetail', (plotId) => ({
        plot: null,
        loading: true,

        async init() {
            await this.loadPlot();
            this.listenToUpdates();
        },

        async loadPlot() {
            try {
                const response = await fetch(`/api/plots/${plotId}`);
                this.plot = await response.json();
            } catch (error) {
                console.error('Failed to load plot:', error);
            } finally {
                this.loading = false;
            }
        },

        listenToUpdates() {
            wsManager?.on('plot_updated', (data) => {
                if (data.id === plotId) {
                    this.plot = data;
                }
            });

            wsManager?.on('plot_like_added', (data) => {
                if (data.plotId === plotId) {
                    this.plot.likes++;
                }
            });

            wsManager?.on('plot_visit_tracked', (data) => {
                if (data.plotId === plotId) {
                    this.plot.visits++;
                }
            });
        },

        async toggleLike() {
            if (!this.plot) return;

            try {
                const response = await fetch(`/api/plots/${plotId}/like`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' }
                });

                if (response.ok) {
                    this.plot.liked = !this.plot.liked;
                    this.plot.likes += this.plot.liked ? 1 : -1;
                }
            } catch (error) {
                console.error('Failed to toggle like:', error);
            }
        }
    }));

    // Sidebar Toggle Component
    Alpine.data('sidebar', () => ({
        open: window.innerWidth >= 992,

        toggle() {
            this.open = !this.open;
        }
    }));

    // Notification Toast Component
    Alpine.data('notifications', () => ({
        items: [],

        init() {
            wsManager?.on('notification', (data) => {
                this.add(data.message, data.type || 'info');
            });
        },

        add(message, type = 'info') {
            const id = Date.now();
            this.items.push({ id, message, type });

            setTimeout(() => {
                this.remove(id);
            }, 5000);
        },

        remove(id) {
            this.items = this.items.filter(item => item.id !== id);
        }
    }));
});

// Initialize WebSocket store when Alpine is ready
document.addEventListener('alpine:initialized', () => {
    Alpine.store('ws').init();
});

// Utility Functions
window.homeClaimUtils = {
    formatNumber(num) {
        if (num >= 1000000) return (num / 1000000).toFixed(1) + 'M';
        if (num >= 1000) return (num / 1000).toFixed(1) + 'K';
        return num.toString();
    },

    formatDate(dateString) {
        const date = new Date(dateString);
        const now = new Date();
        const diff = now - date;

        const minutes = Math.floor(diff / 60000);
        const hours = Math.floor(diff / 3600000);
        const days = Math.floor(diff / 86400000);

        if (minutes < 60) return `vor ${minutes} Min.`;
        if (hours < 24) return `vor ${hours} Std.`;
        if (days < 7) return `vor ${days} Tagen`;

        return date.toLocaleDateString('de-DE');
    }
};
