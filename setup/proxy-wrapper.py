#!/usr/bin/env python3
"""
Local HTTP/HTTPS proxy wrapper that adds authentication headers.

This proxy listens locally and forwards requests to an upstream authenticated proxy.
It handles both HTTP requests and HTTPS CONNECT tunneling.
Uses threading for concurrent connection handling.
"""

import socket
import select
import sys
import os
import base64
import threading
from urllib.parse import urlparse

def relay_data(client_sock, upstream_sock):
    """Bidirectionally relay data between client and upstream sockets."""
    sockets = [client_sock, upstream_sock]
    while True:
        readable, _, exceptional = select.select(sockets, [], sockets, 30)

        if exceptional:
            break

        for sock in readable:
            data = sock.recv(4096)
            if not data:
                return

            if sock is client_sock:
                upstream_sock.sendall(data)
            else:
                client_sock.sendall(data)

class ProxyHandler:
    def __init__(self, upstream_proxy_url):
        """Initialize with upstream proxy URL containing credentials."""
        parsed = urlparse(upstream_proxy_url)
        self.upstream_host = parsed.hostname
        self.upstream_port = parsed.port or 3128

        # Extract credentials for authentication
        if parsed.username and parsed.password:
            credentials = f"{parsed.username}:{parsed.password}"
            self.auth_header = "Basic " + base64.b64encode(credentials.encode()).decode()
        else:
            self.auth_header = None

    def handle_connect(self, client_sock, request_line):
        """Handle HTTPS CONNECT tunnel requests."""
        try:
            # Parse target from CONNECT request
            parts = request_line.split()
            if len(parts) < 2:
                client_sock.sendall(b"HTTP/1.1 400 Bad Request\r\n\r\n")
                return

            target = parts[1]
            print(f"CONNECT request to: {target}", flush=True)

            # Connect to upstream proxy
            upstream_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            upstream_sock.settimeout(30)
            upstream_sock.connect((self.upstream_host, self.upstream_port))

            # Send CONNECT with authentication to upstream proxy
            connect_request = f"CONNECT {target} HTTP/1.1\r\n"
            connect_request += f"Host: {target}\r\n"
            if self.auth_header:
                connect_request += f"Proxy-Authorization: {self.auth_header}\r\n"
            connect_request += "\r\n"

            upstream_sock.sendall(connect_request.encode())

            # Read upstream response
            response = b""
            while b"\r\n\r\n" not in response:
                chunk = upstream_sock.recv(4096)
                if not chunk:
                    break
                response += chunk

            # Forward response to client
            client_sock.sendall(response)

            # If connection established, relay data
            if b"200" in response.split(b"\r\n")[0]:
                relay_data(client_sock, upstream_sock)

            upstream_sock.close()

        except Exception as e:
            print(f"Error handling CONNECT: {e}", flush=True)
            import traceback
            traceback.print_exc()
            try:
                client_sock.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
            except:
                pass

    def handle_http(self, client_sock, request_data):
        """Handle regular HTTP requests."""
        try:
            # Log the request
            first_line = request_data.decode('utf-8', errors='ignore').split('\r\n')[0]
            print(f"HTTP request: {first_line}", flush=True)

            # Connect to upstream proxy
            upstream_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            upstream_sock.settimeout(30)
            upstream_sock.connect((self.upstream_host, self.upstream_port))

            # Inject Proxy-Authorization header if we have credentials
            if self.auth_header:
                lines = request_data.decode('utf-8', errors='ignore').split('\r\n')
                new_lines = []
                header_added = False

                for line in lines:
                    new_lines.append(line)
                    if line and not header_added and ':' in line:
                        # Add auth header after first header line
                        if line.startswith('Host:') or line.startswith('GET') or line.startswith('POST'):
                            continue
                        new_lines.insert(-1, f"Proxy-Authorization: {self.auth_header}")
                        header_added = True

                if not header_added:
                    # Add before blank line
                    new_lines.insert(-2, f"Proxy-Authorization: {self.auth_header}")

                request_data = '\r\n'.join(new_lines).encode()

            # Forward request to upstream
            upstream_sock.sendall(request_data)

            # Relay response back to client
            while True:
                data = upstream_sock.recv(4096)
                if not data:
                    break
                client_sock.sendall(data)

            upstream_sock.close()

        except Exception as e:
            print(f"Error handling HTTP: {e}", flush=True)
            import traceback
            traceback.print_exc()
            try:
                client_sock.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
            except:
                pass

def handle_client(handler, client_sock, client_addr, semaphore):
    """Handle a single client connection in a separate thread."""
    try:
        # Read first line to determine request type
        request_data = b""
        while b"\r\n" not in request_data:
            chunk = client_sock.recv(4096)
            if not chunk:
                break
            request_data += chunk

        if not request_data:
            return

        request_line = request_data.split(b"\r\n")[0].decode('utf-8', errors='ignore')

        # Acquire semaphore to limit concurrent connections
        with semaphore:
            # Route based on method
            if request_line.startswith("CONNECT"):
                handler.handle_connect(client_sock, request_line)
            else:
                # Read rest of HTTP request
                while b"\r\n\r\n" not in request_data:
                    chunk = client_sock.recv(4096)
                    if not chunk:
                        break
                    request_data += chunk
                handler.handle_http(client_sock, request_data)

    except Exception as e:
        print(f"Error in client handler: {e}", flush=True)
    finally:
        try:
            client_sock.close()
        except:
            pass

def main():
    local_port = int(os.environ.get('PROXY_PORT', '8888'))
    max_concurrent = int(os.environ.get('MAX_CONCURRENT_CONNECTIONS', '20'))
    upstream_proxy_url = os.environ.get('http_proxy')

    if not upstream_proxy_url:
        print("Error: http_proxy environment variable not set")
        sys.exit(1)

    print(f"Starting local proxy on port {local_port}")
    print(f"Forwarding to upstream proxy: {upstream_proxy_url}")
    print(f"Max concurrent connections: {max_concurrent}")

    handler = ProxyHandler(upstream_proxy_url)

    # Create semaphore to limit concurrent connections
    connection_semaphore = threading.Semaphore(max_concurrent)

    # Create listening socket
    server_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server_sock.bind(('127.0.0.1', local_port))
    server_sock.listen(50)  # Increased backlog for concurrent connections

    print(f"Proxy ready - configure clients to use http://127.0.0.1:{local_port}")
    print("Press Ctrl+C to stop")

    try:
        while True:
            client_sock, client_addr = server_sock.accept()

            # Spawn a new thread for each connection
            thread = threading.Thread(
                target=handle_client,
                args=(handler, client_sock, client_addr, connection_semaphore),
                daemon=True
            )
            thread.start()

    except KeyboardInterrupt:
        print("\nShutting down proxy...")
    finally:
        server_sock.close()

if __name__ == "__main__":
    main()
