## DELETE http://localhost:46883/echo → 200 OK

### Request Headers
```
DELETE http://localhost:46883/echo
```

### Response Headers
```
content-length: 34
content-type: application/json
date: Sun, 01 Mar 2026 20:47:18 GMT
```

### Response Body
```json
{
  "method": "DELETE",
  "path": "/echo"
}
```

### cURL
```bash
curl 'http://localhost:46883/echo' \
  -X DELETE
```
