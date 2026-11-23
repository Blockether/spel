## DELETE http://localhost:40385/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Mon, 23 Nov 2026 20:40:52 GMT
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
curl 'http://localhost:40385/echo' \
  -X DELETE
```
