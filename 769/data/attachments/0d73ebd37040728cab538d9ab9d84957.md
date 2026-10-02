## DELETE http://localhost:45667/echo → 200 OK

### Response Headers
```
content-length: 34
content-type: application/json
date: Fri, 02 Oct 2026 15:50:46 GMT
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
curl 'http://localhost:45667/echo' \
  -X DELETE
```
