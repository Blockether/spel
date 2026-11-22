## PUT http://localhost:33453/echo → 200 OK

### Request Body
```json
{
  "name": "Eve Updated"
}
```

### Response Headers
```
content-length: 61
content-type: application/json
date: Sun, 22 Nov 2026 16:20:38 GMT
```

### Response Body
```json
{
  "method": "PUT",
  "path": "/echo",
  "body": {
    "name": "Eve Updated"
  }
}
```

### cURL
```bash
curl 'http://localhost:33453/echo' \
  -X PUT \
  -d '{
  "name": "Eve Updated"
}'
```
