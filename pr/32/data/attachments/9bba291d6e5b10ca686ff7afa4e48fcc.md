## PUT http://localhost:34347/echo → 200 OK

### Request Headers
```
PUT http://localhost:34347/echo
```

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
date: Thu, 26 Feb 2026 16:59:02 GMT
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
curl 'http://localhost:34347/echo' \
  -X PUT \
  -d '{"name":"Eve Updated"}'
```
