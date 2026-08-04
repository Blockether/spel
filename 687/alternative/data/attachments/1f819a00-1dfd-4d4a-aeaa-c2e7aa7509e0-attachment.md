## POST http://localhost:39835/echo → 200 OK

### Request Body
```json
{
  "name": "Bob",
  "email": "bob@example.org",
  "role": "admin"
}
```

### Response Headers
```
content-length: 95
content-type: application/json
date: Tue, 04 Aug 2026 22:12:03 GMT
```

### Response Body
```json
{
  "method": "POST",
  "path": "/echo",
  "body": {
    "name": "Bob",
    "email": "bob@example.org",
    "role": "admin"
  }
}
```

### cURL
```bash
curl 'http://localhost:39835/echo' \
  -X POST \
  -d '{
  "name": "Bob",
  "email": "bob@example.org",
  "role": "admin"
}'
```
