/* connection-monitor 连接池历史趋势图 */
(function () {
    'use strict';

    var context = window.MONITOR_CONTEXT || {};
    var state = {hours: 6, granularity: 'auto'};

    var chart = echarts.init(document.getElementById('poolChart'));

    function rangeButtons() {
        var container = document.getElementById('rangeButtons');
        if (!container) { return; }
        container.addEventListener('click', function (event) {
            var button = event.target.closest('button');
            if (!button) { return; }
            container.querySelectorAll('button').forEach(function (b) { b.classList.remove('btn-outline-primary'); b.classList.add('btn-outline-secondary'); b.classList.remove('active'); });
            button.classList.add('btn-outline-primary');
            button.classList.remove('btn-outline-secondary');
            button.classList.add('active');
            state.hours = parseInt(button.getAttribute('data-hours'), 10);
            load();
        });
    }

    function granularityButtons() {
        var container = document.getElementById('granularityButtons');
        if (!container) { return; }
        container.addEventListener('click', function (event) {
            var button = event.target.closest('button');
            if (!button) { return; }
            container.querySelectorAll('button').forEach(function (b) { b.classList.remove('btn-outline-primary'); b.classList.add('btn-outline-secondary'); b.classList.remove('active'); });
            button.classList.add('btn-outline-primary');
            button.classList.remove('btn-outline-secondary');
            button.classList.add('active');
            state.granularity = button.getAttribute('data-granularity');
            load();
        });
    }

    function fetchJson(url, callback) {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', url, true);
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== 4) { return; }
            if (xhr.status !== 200) {
                chart.setOption({title: {text: '历史数据加载失败', left: 'center', top: 'middle'}});
                return;
            }
            callback(JSON.parse(xhr.responseText));
        };
        xhr.send(null);
    }

    function load() {
        var end = Date.now();
        var start = end - state.hours * 3600 * 1000;
        var url = '/api/history?instanceId=' + encodeURIComponent(context.instanceId)
            + '&poolId=' + encodeURIComponent(context.poolId)
            + '&startTime=' + start + '&endTime=' + end
            + '&granularity=' + state.granularity;
        fetchJson(url, render);
    }

    function render(payload) {
        var points = payload.points || [];
        var times = points.map(function (p) { return p.bucketTime; });
        var series = [
            {name: 'Active', key: 'activeConnections', color: '#dc3545'},
            {name: 'Idle', key: 'idleConnections', color: '#198754'},
            {name: 'Total', key: 'totalConnections', color: '#0d6efd'},
            {name: 'Pending', key: 'threadsAwaitingConnection', color: '#fd7e14'}
        ].map(function (item) {
            return {
                name: item.name,
                type: 'line',
                showSymbol: points.length < 100,
                connectNulls: false,
                itemStyle: {color: item.color},
                data: points.map(function (p) {
                    return p[item.key] === undefined ? null : p[item.key];
                })
            };
        });

        chart.setOption({
            tooltip: {trigger: 'axis'},
            legend: {data: ['Active', 'Idle', 'Total', 'Pending']},
            grid: {left: 48, right: 24, top: 40, bottom: 48},
            xAxis: {
                type: 'category',
                data: times,
                axisLabel: {
                    formatter: function (value) {
                        var date = new Date(parseInt(value, 10));
                        function pad(n) { return n < 10 ? '0' + n : '' + n; }
                        return pad(date.getHours()) + ':' + pad(date.getMinutes());
                    }
                }
            },
            yAxis: {type: 'value', minInterval: 1},
            dataZoom: [{type: 'inside'}],
            series: series
        }, true);
    }

    function renderExtraMetrics() {
        var table = document.getElementById('extraMetricsTable');
        if (!table || !context.extraMetrics) { return; }
        var tbody = table.querySelector('tbody');
        var metrics = context.extraMetrics;
        if (typeof metrics === 'string') {
            try { metrics = JSON.parse(metrics); } catch (e) { return; }
        }
        Object.keys(metrics).forEach(function (key) {
            var row = document.createElement('tr');
            var nameCell = document.createElement('td');
            nameCell.textContent = key;
            var valueCell = document.createElement('td');
            valueCell.className = 'text-end';
            valueCell.textContent = String(metrics[key]);
            row.appendChild(nameCell);
            row.appendChild(valueCell);
            tbody.appendChild(row);
        });
    }

    window.addEventListener('resize', function () { chart.resize(); });

    rangeButtons();
    granularityButtons();
    renderExtraMetrics();
    load();
    setInterval(load, 30000);
}());
