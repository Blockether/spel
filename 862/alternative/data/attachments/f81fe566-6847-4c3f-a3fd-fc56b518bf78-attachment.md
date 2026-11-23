## PUT http://localhost:38303/echo → 200 OK

### Request Body
```json
{
  "name": "Alice Updated",
  "email": "alice2@example.org"
}
```

### Response Headers
```
content-length: 92
content-type: application/json
date: Mon, 23 Nov 2026 10:48:46 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Alice Updated",
    "email": "alice2@example.org"
  }
}
```

### cURL
```bash
curl 'http://localhost:38303/echo' \
  -X PUT \
  -d '{
  "name": "Alice Updated",
  "email": "alice2@example.org"
}'
```
