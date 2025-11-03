// Global API prefix
const API_URL = '/api';

// --- Initialization ---

// Check which page we are on
document.addEventListener('DOMContentLoaded', () => {
    if (document.getElementById('login-form')) {
        initLoginPage();
    } else if (document.getElementById('skin-collection')) {
        initDashboardPage();
    }
});

// --- Login Page Logic ---

function initLoginPage() {
    const loginForm = document.getElementById('login-form');
    loginForm.addEventListener('submit', handleLoginSubmit);
}

async function handleLoginSubmit(event) {
    event.preventDefault();
    const username = document.getElementById('username').value;
    const pin = document.getElementById('pin').value;
    
    if (!username || !pin) {
        showMessage('Username and PIN are required.', 'error');
        return;
    }

    try {
        // Kirim username & pin sebagai JSON
        const response = await fetch(`${API_URL}/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, pin })
        });

        const data = await response.json();

        if (response.ok && data.success) {
            showMessage('Login successful! Redirecting...', 'success');
            window.location.href = 'dashboard.html';
        } else {
            showMessage(data.message || 'Invalid username or PIN.', 'error');
        }
    } catch (error) {
        showMessage('Error connecting to the server. Is it running?', 'error');
        console.error('Login error:', error);
    }
}

// --- Dashboard Page Logic ---

let skinViewerInstances = new Map(); // Map to store 3D viewers by skinId

async function initDashboardPage() {
    const logoutBtn = document.getElementById('logout-btn');
    if (logoutBtn) {
        logoutBtn.addEventListener('click', handleLogout);
    }

    const uploadForm = document.getElementById('upload-form');
    if (uploadForm) {
        uploadForm.addEventListener('submit', handleUploadSubmit);
    }
    
    await fetchDashboardData();
}

async function fetchDashboardData() {
    try {
        const response = await fetch(`${API_URL}/dashboard/data`);
        
        if (!response.ok) {
            if (response.status === 401 || response.status === 403) {
                window.location.href = 'index.html';
                return;
            }
            throw new Error(`Server error: ${response.statusText}`);
        }

        const data = await response.json();

        if (data.success) {
            document.getElementById('username-display').textContent = data.username;
            document.getElementById('skin-count').textContent = data.skins.length;
            document.getElementById('max-skins').textContent = data.maxSkins;

            if (data.skins.length >= data.maxSkins) {
                document.getElementById('upload-card').classList.add('hidden');
            } else {
                document.getElementById('upload-card').classList.remove('hidden');
            }

            const collectionDiv = document.getElementById('skin-collection');
            collectionDiv.innerHTML = '';
            
            skinViewerInstances.forEach(viewer => viewer.dispose());
            skinViewerInstances.clear();

            data.skins.forEach(skin => {
                const skinElement = createSkinElement(skin);
                collectionDiv.appendChild(skinElement);
            });
        } else {
            showMessage(data.message || 'Failed to load dashboard data.', 'error');
        }

    } catch (error) {
        showMessage('Error fetching dashboard data. Redirecting to login.', 'error');
        console.error('Fetch data error:', error);
        setTimeout(() => window.location.href = 'index.html', 2000);
    }
}

function createSkinElement(skin) {
    const template = document.getElementById('skin-item-template');
    const el = template.content.cloneNode(true).firstElementChild;
    const skinId = skin.id;

    el.dataset.skinId = skinId;
    el.querySelector('.skin-name').textContent = skin.name;

    const preview = el.querySelector('.skin-preview');
    if (window.skinview3d) {
        try {
            let skinViewer = new skinview3d.SkinViewer({
                canvas: document.createElement('canvas'),
                width: 150,
                height: 150,
                skin: {
                    value: skin.texture,
                    signature: skin.signature
                }
            });
            preview.innerHTML = '';
            preview.appendChild(skinViewer.canvas);
            
            let control = skinview3d.createOrbitControls(skinViewer);
            control.enableRotate = true;
            control.enableZoom = false;

            skinViewer.animation = new skinview3d.WalkingAnimation();
            skinViewer.animation.speed = 1.5;
            
            skinViewerInstances.set(skinId, skinViewer);
        } catch (e) {
            console.error("Failed to load 3D skin:", e);
            preview.textContent = "Preview error";
        }
    } else {
        preview.textContent = '3D Preview disabled';
    }

    el.querySelector('.btn-apply').addEventListener('click', () => handleApplySkin(skinId, el));
    el.querySelector('.btn-delete').addEventListener('click', () => handleDeleteSkin(skinId, el));

    return el;
}

async function handleApplySkin(skinId, element) {
    try {
        const response = await fetch(`${API_URL}/dashboard/apply`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ skinId })
        });
        const data = await response.json();
        if (response.ok && data.success) {
            showMessage(data.message || 'Skin applied!', 'success');
        } else {
            showMessage(data.message || 'Failed to apply skin.', 'error');
        }
    } catch (error) {
        showMessage('Server error while applying skin.', 'error');
    }
}

async function handleDeleteSkin(skinId, element) {
    if (!confirm('Are you sure you want to delete this skin?')) return;
    
    try {
        const response = await fetch(`${API_URL}/dashboard/delete`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ skinId })
        });
        const data = await response.json();
        if (response.ok && data.success) {
            showMessage(data.message || 'Skin deleted.', 'success');
            element.remove();
            if (skinViewerInstances.has(skinId)) {
                skinViewerInstances.get(skinId).dispose();
                skinViewerInstances.delete(skinId);
            }
            await fetchDashboardData();
        } else {
            showMessage(data.message || 'Failed to delete skin.', 'error');
        }
    } catch (error) {
        showMessage('Server error while deleting skin.', 'error');
    }
}

async function handleUploadSubmit(event) {
    event.preventDefault();
    const form = event.target;
    const fileInput = document.getElementById('skin-file');
    const nameInput = document.getElementById('skin-name');
    const uploadBtn = document.getElementById('upload-btn');
    
    const file = fileInput.files[0];
    const skinName = nameInput.value;
    
    if (!file || !skinName) {
        showMessage('Skin name and file are required.', 'error');
        return;
    }
    
    if (file.type !== 'image/png') {
        showMessage('Only .png files are allowed.', 'error');
        return;
    }

    uploadBtn.disabled = true;
    uploadBtn.textContent = 'Uploading...';

    const formData = new FormData();
    formData.append('skinFile', file, skinName + '.png');

    try {
        const response = await fetch(`${API_URL}/dashboard/upload`, {
            method: 'POST',
            body: formData
        });

        const data = await response.json();
        
        if (response.ok && data.success) {
            showMessage(data.message || 'Upload successful!', 'success');
            form.reset();
            await fetchDashboardData();
        } else {
            showMessage(data.message || 'Upload failed.', 'error');
        }

    } catch (error) {
        showMessage('Server error during upload.', 'error');
        console.error('Upload error:', error);
    } finally {
        uploadBtn.disabled = false;
        uploadBtn.textContent = 'Upload Skin';
    }
}

async function handleLogout() {
    try {
        await fetch(`${API_URL}/logout`, { method: 'POST' });
    } catch (error) {
        // Ignore
    } finally {
        window.location.href = 'index.html';
    }
}

// --- Utility Functions ---
function showMessage(message, type) {
    // FIX: gunakan id 'message' sesuai HTML
    const box = document.getElementById('message');
    if (!box) return;
    box.textContent = message;
    box.classList.remove('error', 'success');
    box.classList.add(type === 'success' ? 'success' : 'error');
    box.style.display = 'block';
    setTimeout(() => { box.style.display = 'none'; }, 4000);
}
