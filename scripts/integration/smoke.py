#!/usr/bin/env python3
"""TLS loopback real del núcleo; no equivale a rendimiento LAN ni prueba Windows/J4+."""
import argparse
import base64
import hashlib
import http.client
import json
from pathlib import Path
import queue
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import uuid


class PinnedConnection(http.client.HTTPSConnection):
    def __init__(self, port, certificate, pin):
        context = ssl.create_default_context(cadata=ssl.DER_cert_to_PEM_cert(certificate))
        super().__init__('file-transfer.local', port, context=context, timeout=20)
        self.pin = pin

    def connect(self):
        raw = socket.create_connection(('127.0.0.1', self.port), timeout=self.timeout)
        self.sock = self._context.wrap_socket(raw, server_hostname=self.host)
        if hashlib.sha256(self.sock.getpeercert(binary_form=True)).hexdigest() != self.pin:
            self.close()
            raise ssl.SSLError('pin mismatch')


def run(binary, mib):
    with tempfile.TemporaryDirectory(prefix='file-transfer-smoke-') as tmp:
        root = Path(tmp)
        data, inbox = root / 'state', root / 'inbox'
        inbox.mkdir()
        source = root / 'sample.bin'
        digest = hashlib.sha256()
        block = bytes(range(256)) * 256
        with source.open('wb') as f:
            for _ in range(mib * 16):
                f.write(block)
                digest.update(block)
        expected, size = digest.hexdigest(), source.stat().st_size
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        process = subprocess.Popen([str(binary), 'serve', '--data', str(data), '--bind', '127.0.0.1', '--port', str(port)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
        lines = queue.Queue()
        threading.Thread(target=lambda: [lines.put(line) for line in process.stdout], daemon=True).start()
        def receive():
            try:
                return json.loads(lines.get(timeout=30))
            except queue.Empty as error:
                raise RuntimeError('Core did not respond within 30 seconds') from error
        def command(name, **values):
            process.stdin.write(json.dumps({'command': name, **values}) + '\n')
            process.stdin.flush()
            response = receive()
            assert response.get('ok'), response
            return response['result']
        connection = None
        try:
            assert receive()['event'] == 'started'
            descriptor = command('pairing')
            certificate = base64.b64decode(json.loads((data / 'identity.json').read_text())['cert'])
            wrong = PinnedConnection(port, certificate, '0' * 64)
            try:
                wrong.connect()
                raise AssertionError('Incorrect pin accepted')
            except ssl.SSLError:
                pass
            finally:
                wrong.close()
            connection = PinnedConnection(port, certificate, descriptor['cert_sha256'])
            peer = str(uuid.uuid4())
            headers = {'Accept-Encoding': 'identity'}
            def request(method, path, value=None, status=200, body=None):
                extra = dict(headers)
                if value is not None:
                    body = json.dumps(value).encode()
                    extra['Content-Type'] = 'application/json'
                elif body is not None:
                    extra.update({'Content-Type': 'application/octet-stream', 'Content-Length': str(size)})
                connection.request(method, '/v1/' + path, body=body, headers=extra)
                response = connection.getresponse()
                content = response.read(16385)
                assert len(content) <= 16384
                result = json.loads(content)
                assert response.status == status, (response.status, result.get('error', {}).get('code'))
                return result
            request('GET', 'offers', status=401)
            pair = request('POST', 'pair', {'pairing_token': descriptor['pairing_token'], 'device_id': peer, 'device_name': 'Loopback test'}, status=201)
            headers.update({'Authorization': 'Bearer ' + pair['credential'], 'X-Device-Id': peer})
            request('POST', 'transfers', {'file_name': source.name, 'size_bytes': str(size)}, status=409)
            command('receive', enabled=True)
            measurements = {}
            for attempt in range(2):
                transfer = request('POST', 'transfers', {'file_name': source.name, 'size_bytes': str(size)}, status=201)
                tid = transfer['transfer_id']
                command('accept_upload', transfer_id=tid, destination=str(inbox))
                started = time.monotonic()
                with source.open('rb') as file:
                    result = request('PUT', f'transfers/{tid}/content', body=file)
                assert result['state'] == 'awaiting_verification' and result['sha256'] == expected
                assert request('POST', f'transfers/{tid}/complete', {'sha256': expected})['state'] == 'completed'
                assert request('POST', f'transfers/{tid}/complete', {'sha256': expected})['state'] == 'completed'
                measurements[f'upload_{attempt + 1}_seconds'] = time.monotonic() - started
            published = sorted(inbox.glob('*.bin'))
            assert len(published) == 2
            assert all(hashlib.file_digest(p.open('rb'), 'sha256').hexdigest() == expected for p in published)
            offered = command('offer', peer_id=peer, source=str(source))
            tid = offered['transfer_id']
            assert request('GET', 'offers')['transfers'][0]['transfer_id'] == tid
            request('GET', f'transfers/{tid}/content', status=409)
            request('POST', f'transfers/{tid}/accept', {})
            started = time.monotonic()
            connection.request('GET', f'/v1/transfers/{tid}/content', headers=headers)
            response = connection.getresponse()
            assert response.status == 200 and int(response.getheader('Content-Length')) == size
            actual, count = hashlib.sha256(), 0
            while block := response.read(65536):
                actual.update(block)
                count += len(block)
            assert count == size and actual.hexdigest() == expected
            limit = time.monotonic() + 5
            while request('GET', f'transfers/{tid}')['state'] != 'awaiting_verification':
                assert time.monotonic() < limit
                time.sleep(0.05)
            assert request('POST', f'transfers/{tid}/complete', {'sha256': expected})['state'] == 'completed'
            measurements['download_seconds'] = time.monotonic() - started
            command('revoke', peer_id=peer)
            request('GET', 'offers', status=401)
            command('stop')
            print(json.dumps({'environment': 'Linux TLS loopback, not LAN or hardware', 'bytes_per_transfer': size, 'sha256': expected, 'checks': ['wrong_pin', 'authentication', 'receive_disabled', 'upload_integrity', 'collision_no_overwrite', 'complete_idempotent', 'download_consent', 'download_integrity', 'revocation'], **measurements}, indent=2))
        finally:
            if connection:
                connection.close()
            if process.poll() is None:
                process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binary', type=Path)
    parser.add_argument('--mib', type=int, default=16)
    args = parser.parse_args()
    if not 1 <= args.mib <= 4096:
        parser.error('--mib must be between 1 and 4096')
    run(args.binary.resolve(), args.mib)
