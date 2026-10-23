## DELETE http://localhost:39835/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 23 Oct 2026 21:22:39 GMT
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
curl 'http://localhost:39835/echo' \
  -X DELETE
```
