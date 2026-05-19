const express = require('express');
const http = require('http');
const { Server } = require('socket.io');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3000;
const DATA_FILE = path.join(__dirname, 'data.json');
const ADMIN_PASS = '1234';

// Data structure
let data = { laBanca: 0, premios: 0, jugadores: [] };

function loadData() {
    try {
        if (fs.existsSync(DATA_FILE)) {
            const raw = fs.readFileSync(DATA_FILE, 'utf8');
            data = JSON.parse(raw);
        }
    } catch (e) { console.error('Error loading data:', e.message); }
}

function saveData() {
    try {
        fs.writeFileSync(DATA_FILE, JSON.stringify(data, null, 2), 'utf8');
    } catch (e) { console.error('Error saving data:', e.message); }
}

loadData();

// Keep a map of dni -> socketId for connected players
const connectedPlayers = new Map();

const app = express();
const server = http.createServer(app);
const io = new Server(server, { cors: { origin: '*' } });

app.use(express.static(path.join(__dirname, '..', 'slot-machine-apk', 'www')));

// Penalty/cost table
function getCost(bonus) {
    if (bonus === 2) return 5;
    if (bonus === 3) return 10;
    return 2;
}

// Fruit data
const FRUITS = [
    { name: 'Fresa', emoji: '🍓', premio4: 200, premio3: 3 },
    { name: 'Plátano', emoji: '🍌', premio4: 70, premio3: 3 },
    { name: 'Manzana', emoji: '🍎', premio4: 100, premio3: 3 },
    { name: 'Melocotón', emoji: '🍑', premio4: 50, premio3: 3 },
    { name: 'Higo', emoji: '🥝', premio4: 25, premio3: 3 },
    { name: 'Uvas', emoji: '🍇', premio4: 25, premio3: 3 },
    { name: 'Nuez', emoji: '🥜', premio4: 20, premio3: 3 },
    { name: 'Pera', emoji: '🍐', premio4: 20, premio3: 3 },
    { name: 'Corazón', emoji: '❤️', premio4: 15, premio3: 3 },
    { name: 'Diamante', emoji: '💎', premio4: 15, premio3: 3 }
];

function calcScore(fruits) {
    const counts = {};
    for (const f of fruits) counts[f] = (counts[f] || 0) + 1;
    const vals = Object.values(counts).sort((a, b) => b - a);
    const keys = Object.keys(counts);
    if (vals.length === 1 && vals[0] === 4) {
        const fr = FRUITS.find(f => f.name === keys[0]);
        return { euros: fr ? fr.premio4 : 0, type: '4', fruit: keys[0] };
    }
    if (vals.length === 2 && vals[0] === 3) {
        const fr = FRUITS.find(f => f.name === keys[0]);
        return { euros: fr ? fr.premio3 : 0, type: '3', fruit: keys[0] };
    }
    if (vals.length === 2 && vals[0] === 2) return { euros: 1, type: '2+2' };
    if (vals.length === 3 && vals[0] === 2) return { euros: 0, type: '2' };
    return { euros: -1, type: 'none' };
}

function aplicarReglaPremio(euros) {
    if (euros <= 0) return 0;
    const maxPremios = data.premios * 0.5;
    return Math.max(0, Math.min(euros, maxPremios));
}

function broadcastCounters() {
    io.emit('counters', { laBanca: data.laBanca, premios: data.premios });
}

function broadcastPlayers() {
    io.emit('players', data.jugadores.map(j => ({
        name: j.name, dni: j.dni, monedero: j.monedero,
        connected: connectedPlayers.has(j.dni)
    })));
}

io.on('connection', (socket) => {
    console.log(`Cliente conectado: ${socket.id}`);

    // Register
    socket.on('register', (playerData, callback) => {
        const { name, dni, phone, age, country, prov } = playerData;
        if (!name || !dni || !phone || !country || !prov) {
            return callback({ error: 'Completa todos los campos' });
        }
        if (age < 18) return callback({ error: 'Debes ser mayor de 18 años' });
        if (data.jugadores.find(j => j.dni === dni)) {
            return callback({ error: 'Ya existe un jugador con ese DNI' });
        }
        const jug = { name, dni, phone, age, country, prov, monedero: 0, bonus: 1 };
        data.jugadores.push(jug);
        connectedPlayers.set(dni, socket.id);
        socket.data.dni = dni;
        saveData();
        broadcastPlayers();
        callback({ ok: true, player: jug });
    });

    // Login
    socket.on('login', (dni, callback) => {
        const jug = data.jugadores.find(j => j.dni === dni);
        if (!jug) return callback({ error: 'No se encontró jugador con ese DNI' });
        connectedPlayers.set(dni, socket.id);
        socket.data.dni = dni;
        broadcastPlayers();
        callback({ ok: true, player: jug });
    });

    // Logout
    socket.on('logout', () => {
        if (socket.data.dni) {
            connectedPlayers.delete(socket.data.dni);
            delete socket.data.dni;
            broadcastPlayers();
        }
    });

    // Get player data (refresh)
    socket.on('getPlayer', (dni, callback) => {
        const jug = data.jugadores.find(j => j.dni === dni);
        if (jug) callback({ ok: true, player: jug });
        else callback({ error: 'No encontrado' });
    });

    // Spin
    socket.on('spin', (bonus, callback) => {
        const dni = socket.data.dni;
        if (!dni) return callback({ error: 'No has iniciado sesión' });
        const jug = data.jugadores.find(j => j.dni === dni);
        if (!jug) return callback({ error: 'Jugador no encontrado' });

        const cost = getCost(bonus);
        if (jug.monedero < cost) return callback({ error: 'Saldo insuficiente', cost });

        jug.monedero -= cost;
        const aBanca = cost * 0.3;
        const aPremios = cost * 0.7;
        data.laBanca += aBanca;
        data.premios += aPremios;

        const targets = Array.from({ length: 4 }, () => Math.floor(Math.random() * 10));
        const names = targets.map(i => FRUITS[i].name);
        const result = calcScore(names);

        if (result.euros > 0) {
            const premioFinal = aplicarReglaPremio(result.euros);
            if (premioFinal > 0) {
                jug.monedero += premioFinal;
                data.premios -= premioFinal;
                if (data.premios < 0) data.premios = 0;
                result.premioFinal = premioFinal;
            }
        }

        saveData();
        broadcastCounters();
        broadcastPlayers();

        callback({
            ok: true,
            targets,
            result,
            newMonedero: jug.monedero,
            laBanca: data.laBanca,
            premios: data.premios,
            cost
        });
    });

    // Deposit
    socket.on('deposit', (amount, callback) => {
        const dni = socket.data.dni;
        if (!dni) return callback({ error: 'No has iniciado sesión' });
        const jug = data.jugadores.find(j => j.dni === dni);
        if (!jug || !amount || amount <= 0) return callback({ error: 'Cantidad inválida' });

        data.laBanca += amount * 0.3;
        data.premios += amount * 0.7;
        jug.monedero += amount;
        saveData();
        broadcastCounters();
        broadcastPlayers();
        callback({ ok: true, newMonedero: jug.monedero });
    });

    // Withdraw
    socket.on('withdraw', (callback) => {
        const dni = socket.data.dni;
        if (!dni) return callback({ error: 'No has iniciado sesión' });
        const jug = data.jugadores.find(j => j.dni === dni);
        if (!jug || jug.monedero <= 0) return callback({ error: 'Saldo insuficiente' });

        const amount = jug.monedero;
        jug.monedero = 0;
        saveData();
        broadcastPlayers();
        callback({ ok: true, amount });
    });

    // Admin login
    socket.on('adminLogin', (pass, callback) => {
        if (pass !== ADMIN_PASS) return callback({ error: 'Clave incorrecta' });
        callback({
            ok: true,
            laBanca: data.laBanca,
            premios: data.premios,
            jugadores: data.jugadores.map(j => ({
                name: j.name, dni: j.dni, phone: j.phone, age: j.age,
                country: j.country, prov: j.prov, monedero: j.monedero,
                connected: connectedPlayers.has(j.dni)
            }))
        });
    });

    // Admin add funds
    socket.on('adminAddFunds', (amount, callback) => {
        if (!amount || amount <= 0) return callback({ error: 'Cantidad inválida' });
        data.premios += amount;
        saveData();
        broadcastCounters();
        callback({ ok: true, premios: data.premios });
    });

    // Disconnect
    socket.on('disconnect', () => {
        console.log(`Cliente desconectado: ${socket.id}`);
        if (socket.data.dni) {
            connectedPlayers.delete(socket.data.dni);
            broadcastPlayers();
        }
    });
});

server.listen(PORT, '0.0.0.0', () => {
    console.log(`🚀 Servidor MIKEUROS corriendo en http://0.0.0.0:${PORT}`);
    console.log(`   Jugadores registrados: ${data.jugadores.length}`);
    console.log(`   La_Banca: ${data.laBanca.toFixed(2)}€  Premios: ${data.premios.toFixed(2)}€`);
});
