## POST http://localhost:35509/echo → 200 OK

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
date: Sat, 24 Oct 2026 08:08:32 GMT
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
curl 'http://localhost:35509/echo' \
  -X POST \
  -d '{
  "name": "Bob",
  "email": "bob@example.org",
  "role": "admin"
}'
```
